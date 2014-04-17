/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop.jobtracker;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.*;
import org.gridgain.grid.hadoop.*;
import org.gridgain.grid.kernal.processors.hadoop.*;
import org.gridgain.grid.kernal.processors.hadoop.taskexecutor.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.typedef.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;

import static org.gridgain.grid.kernal.processors.hadoop.jobtracker.GridHadoopJobPhase.*;

/**
 * Hadoop job tracker.
 */
public class GridHadoopJobTracker extends GridHadoopComponent {
    /** System cache. */
    private GridCacheProjection<GridHadoopJobId, GridHadoopJobMetadata> jobMetaPrj;

    /** Map-reduce execution planner. */
    private GridHadoopMapReducePlanner mrPlanner;

    /** Locally active jobs. */
    private ConcurrentMap<GridHadoopJobId, JobLocalState> activeJobs = new ConcurrentHashMap8<>();

    /** Locally requested finish futures. */
    private ConcurrentMap<GridHadoopJobId, GridFutureAdapter<GridHadoopJobId>> activeFinishFuts =
        new ConcurrentHashMap8<>();

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws GridException {
        super.onKernalStart();

        GridCache<Object, Object> sysCache = ctx.kernalContext().cache().cache(ctx.systemCacheName());

        mrPlanner = ctx.planner();

        jobMetaPrj = sysCache.projection(GridHadoopJobId.class, GridHadoopJobMetadata.class);

        GridCacheContinuousQuery<GridHadoopJobId, GridHadoopJobMetadata> qry = jobMetaPrj.queries()
            .createContinuousQuery();

        qry.callback(new GridBiPredicate<UUID,
            Collection<Map.Entry<GridHadoopJobId, GridHadoopJobMetadata>>>() {
            @Override public boolean apply(UUID nodeId,
                Collection<Map.Entry<GridHadoopJobId, GridHadoopJobMetadata>> evts) {
                processJobMetadata(nodeId, evts);

                return true;
            }
        });

        qry.execute();
    }

    /**
     * Submits execution of Hadoop job to grid.
     *
     * @param jobId Job ID.
     * @param info Job info.
     * @return Job completion future.
     */
    public GridFuture<?> submit(GridHadoopJobId jobId, GridHadoopJobInfo info) {
        try {
            GridHadoopJob job = ctx.jobFactory().createJob(jobId, info);

            Collection<GridHadoopFileBlock> blocks = job.input();

            GridHadoopMapReducePlan mrPlan = mrPlanner.preparePlan(blocks, ctx.nodes(), job, null);

            GridHadoopJobMetadata meta = new GridHadoopJobMetadata(jobId, info);

            meta.mapReducePlan(mrPlan);

            meta.pendingBlocks(allBlocks(mrPlan));

            GridFutureAdapter<GridHadoopJobId> completeFut = new GridFutureAdapter<>();

            GridFutureAdapter<GridHadoopJobId> old = activeFinishFuts.put(jobId, completeFut);

            assert old == null : "Duplicate completion future [jobId=" + jobId + ", old=" + old + ']';

            jobMetaPrj.put(jobId, meta);

            return completeFut;
        }
        catch (GridException e) {
            return new GridFinishedFutureEx<>(e);
        }
    }

    /**
     * Gets hadoop job status for given job ID.
     *
     * @param jobId Job ID to get status for.
     * @return Job status for given job ID or {@code null} if job was not found.
     */
    @Nullable public GridHadoopJobStatus status(GridHadoopJobId jobId) {
        return null;
    }

    /**
     * Callback from task executor invoked when a task has been finished.
     *
     * @param taskInfo Task info.
     * @param status Task status.
     */
    public void onTaskFinished(GridHadoopTaskInfo taskInfo, GridHadoopTaskStatus status) {
        GridHadoopJobId jobId = taskInfo.jobId();

        switch (taskInfo.type()) {
            case MAP: {
                jobMetaPrj.transformAsync(jobId, new RemoveMapperClosure(taskInfo.fileBlock()));

                break;
            }

            case REDUCE: {
                jobMetaPrj.transformAsync(jobId, new RemoveReducerClosure(taskInfo.taskNumber()));

                break;
            }

            case COMBINE: {
                break;
            }
        }
    }

    /**
     * @param blocks Blocks to init nodes for.
     */
    private void initBlockNodes(Collection<GridHadoopFileBlock> blocks) {

    }

    /**
     * Gets all file blocks for given hadoop map-reduce plan.
     *
     * @param plan Map-reduce plan.
     * @return Collection of all file blocks that should be processed.
     */
    private Collection<GridHadoopFileBlock> allBlocks(GridHadoopMapReducePlan plan) {
        Collection<GridHadoopFileBlock> res = new HashSet<>();

        for (UUID nodeId : plan.mapperNodeIds())
            res.addAll(plan.mappers(nodeId));

        return res;
    }

    /**
     * @param origNodeId Originating node ID.
     * @param updated Updated cache entries.
     */
    private void processJobMetadata(UUID origNodeId,
        Iterable<Map.Entry<GridHadoopJobId, GridHadoopJobMetadata>> updated) {
        for (Map.Entry<GridHadoopJobId, GridHadoopJobMetadata> entry : updated) {
            GridHadoopJobId jobId = entry.getKey();
            GridHadoopJobMetadata meta = entry.getValue();

            JobLocalState state = activeJobs.get(jobId);

            if (state == null)
                state = F.addIfAbsent(activeJobs, jobId, new JobLocalState());

            GridHadoopJob job = ctx.jobFactory().createJob(jobId, meta.jobInfo());

            UUID locNodeId = ctx.localNodeId();

            switch (meta.phase()) {
                case PHASE_MAP: {
                    if (meta.pendingBlocks().isEmpty() && ctx.jobUpdateLeader()) {
                        jobMetaPrj.transformAsync(jobId, new UpdatePhaseClosure(PHASE_REDUCE));

                        return;
                    }

                    // Check if we should initiate new task on local node.
                    Collection<GridHadoopFileBlock> mappers = meta.mapReducePlan().mappers(locNodeId);

                    if (mappers != null) {
                        Collection<GridHadoopTask> tasks = new ArrayList<>();

                        for (GridHadoopFileBlock block : mappers) {
                            if (state.addMapper(block)) {
                                // TODO task number and attempt - how do we count them?
                                GridHadoopTaskInfo taskInfo = new GridHadoopTaskInfo(locNodeId,
                                    GridHadoopTaskType.MAP, jobId, 0, 0, block);

                                GridHadoopTask task = job.createTask(taskInfo);

                                tasks.add(task);
                            }
                        }

                        ctx.taskExecutor().run(tasks);
                    }

                    break;
                }


                case PHASE_REDUCE: {
                    if (meta.pendingReducers().isEmpty() && ctx.jobUpdateLeader()) {
                        GridCacheEntry<GridHadoopJobId, GridHadoopJobMetadata> jobEntry = jobMetaPrj.entry(jobId);

                        jobEntry.timeToLive(10_000); // TODO configuration?

                        jobEntry.transformAsync(new UpdatePhaseClosure(PHASE_COMPLETE));

                        return;
                    }

                    int[] reducers = meta.mapReducePlan().reducers(locNodeId);

                    if (reducers != null) {
                        Collection<GridHadoopTask> tasks = new ArrayList<>();

                        for (int rdc : reducers) {
                            if (state.addReducer(rdc)) {
                                GridHadoopTaskInfo taskInfo = new GridHadoopTaskInfo(locNodeId,
                                    GridHadoopTaskType.REDUCE, jobId, rdc, 0, null);

                                GridHadoopTask task = job.createTask(taskInfo);

                                tasks.add(task);
                            }
                        }

                        ctx.taskExecutor().run(tasks);
                    }

                    break;
                }

                case PHASE_COMPLETE: {
                    state = activeJobs.remove(jobId);

                    assert state != null;

                    GridFutureAdapter<GridHadoopJobId> finishFut = activeFinishFuts.remove(jobId);

                    if (finishFut != null)
                        finishFut.onDone(jobId); // TODO exception.

                    break;
                }

                default:
                    assert false;
            }
        }
    }

    /**
     * Job tracker's local job state.
     */
    private static class JobLocalState {
        /**
         * Adds mapper for local job state if this mapper has not been added yet.
         *
         * @param block Block to add.
         * @return {@code True} if mapper was not added to this local node  yet.
         */
        public synchronized boolean addMapper(GridHadoopFileBlock block) {
            // TODO.
            return true;
        }

        /**
         * Adds reducer for local job state if this reducer has not been added yet.
         *
         * @param rdcIdx Reducer index.
         * @return {@code True} if reducer was not added to this local node yet.
         */
        public synchronized boolean addReducer(int rdcIdx) {
            // TODO.
            return true;
        }
    }

    /**
     * Update job phase transform closure.
     */
    private static class UpdatePhaseClosure implements GridClosure<GridHadoopJobMetadata, GridHadoopJobMetadata> {
        /** Phase to update. */
        private GridHadoopJobPhase phase;

        /**
         * @param phase Phase to update.
         */
        private UpdatePhaseClosure(GridHadoopJobPhase phase) {
            this.phase = phase;
        }

        /** {@inheritDoc} */
        @Override public GridHadoopJobMetadata apply(GridHadoopJobMetadata meta) {
            GridHadoopJobMetadata cp = new GridHadoopJobMetadata(meta);

            cp.phase(phase);

            return cp;
        }
    }

    /**
     * Remove mapper transform closure.
     */
    private static class RemoveMapperClosure implements GridClosure<GridHadoopJobMetadata, GridHadoopJobMetadata> {
        /** Mapper block to remove. */
        private GridHadoopFileBlock block;

        /**
         * @param block Mapper block to remove.
         */
        private RemoveMapperClosure(GridHadoopFileBlock block) {
            this.block = block;
        }

        /** {@inheritDoc} */
        @Override public GridHadoopJobMetadata apply(GridHadoopJobMetadata meta) {
            GridHadoopJobMetadata cp = new GridHadoopJobMetadata(meta);

            Collection<GridHadoopFileBlock> blocksCp = new HashSet<>(cp.pendingBlocks());

            blocksCp.remove(block);

            cp.pendingBlocks(blocksCp);

            return cp;
        }
    }

    /**
     * Remove reducer transform closure.
     */
    private static class RemoveReducerClosure implements GridClosure<GridHadoopJobMetadata, GridHadoopJobMetadata> {
        /** Mapper block to remove. */
        private int rdc;

        /**
         * @param rdc Reducer to remove.
         */
        private RemoveReducerClosure(int rdc) {
            this.rdc = rdc;
        }

        /** {@inheritDoc} */
        @Override public GridHadoopJobMetadata apply(GridHadoopJobMetadata meta) {
            GridHadoopJobMetadata cp = new GridHadoopJobMetadata(meta);

            Collection<Integer> rdcCp = new HashSet<>(cp.pendingReducers());

            rdcCp.remove(rdc);

            cp.pendingReducers(rdcCp);

            return cp;
        }
    }
}
