/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop;

import org.gridgain.grid.*;
import org.gridgain.grid.hadoop.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.processors.hadoop.jobtracker.*;
import org.gridgain.grid.kernal.processors.hadoop.shuffle.*;
import org.gridgain.grid.kernal.processors.hadoop.taskexecutor.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.util.*;

/**
 * Hadoop accelerator context.
 */
public class GridHadoopContext {
    /** Kernal context. */
    private GridKernalContext ctx;

    /** Hadoop configuration. */
    private GridHadoopConfiguration cfg;

    /** Job tracker. */
    private GridHadoopJobTracker jobTracker;

    /** External task executor. */
    private GridHadoopTaskExecutorAdapter taskExecutor;

    /** */
    private GridHadoopShuffle shuffle;

    /** Managers list. */
    private List<GridHadoopComponent> components = new ArrayList<>();

    /**
     * @param ctx Kernal context.
     */
    public GridHadoopContext(
        GridKernalContext ctx,
        GridHadoopConfiguration cfg,
        GridHadoopJobTracker jobTracker,
        GridHadoopTaskExecutorAdapter taskExecutor,
        GridHadoopShuffle shuffle
    ) {
        this.ctx = ctx;
        this.cfg = cfg;

        this.jobTracker = add(jobTracker);
        this.taskExecutor = add(taskExecutor);
        this.shuffle = add(shuffle);
    }

    /**
     * Gets list of managers.
     *
     * @return List of managers.
     */
    public List<GridHadoopComponent> components() {
        return components;
    }

    /**
     * Gets kernal context.
     *
     * @return Grid kernal context instance.
     */
    public GridKernalContext kernalContext() {
        return ctx;
    }

    /**
     * Gets Hadoop configuration.
     *
     * @return Hadoop configuration.
     */
    public GridHadoopConfiguration configuration() {
        return cfg;
    }

    /**
     * Gets local node ID. Shortcut for {@code kernalContext().localNodeId()}.
     *
     * @return Local node ID.
     */
    public UUID localNodeId() {
        return ctx.localNodeId();
    }

    /**
     * @return Hadoop-enabled nodes.
     */
    public Collection<GridNode> nodes() {
        return ctx.discovery().cacheNodes(CU.SYS_CACHE_HADOOP, ctx.discovery().topologyVersion());
    }

    /**
     * @return {@code True} if
     */
    public boolean jobUpdateLeader() {
        long minOrder = Long.MAX_VALUE;
        GridNode minOrderNode = null;

        for (GridNode node : nodes()) {
            if (node.order() < minOrder) {
                minOrder = node.order();
                minOrderNode = node;
            }
        }

        assert minOrderNode != null;

        return localNodeId().equals(minOrderNode.id());
    }

    /**
     * @return Jon tracker instance.
     */
    public GridHadoopJobTracker jobTracker() {
        return jobTracker;
    }

    /**
     * @return Task executor.
     */
    public GridHadoopTaskExecutorAdapter taskExecutor() {
        return taskExecutor;
    }

    /**
     * @return Shuffle.
     */
    public GridHadoopShuffle shuffle() {
        return shuffle;
    }

    /**
     * @return Map-reduce planner.
     */
    public GridHadoopMapReducePlanner planner() {
        return cfg.getMapReducePlanner();
    }

    /**
     * @return Job factory.
     */
    public GridHadoopJobFactory jobFactory() {
        return cfg.getJobFactory();
    }

    /**
     * Adds component.
     *
     * @param c Component to add.
     * @return Added manager.
     */
    private <C extends GridHadoopComponent> C add(C c) {
        components.add(c);

        return c;
    }
}
