package org.apache.giraph.bsp;

import java.io.IOException;

import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.zookeeper.KeeperException;

import org.apache.giraph.graph.BspService.State;

/**
 * At most, there will be one active master at a time, but many threads can
 * be trying to be the active master.
 */
@SuppressWarnings("rawtypes")
public interface CentralizedServiceMaster<
        I extends WritableComparable,
        V extends Writable,
        E extends Writable,
        M extends Writable>
        extends CentralizedService<I, V, E, M> {
    /**
     * State of a coordinated superstep
     */
    public enum SuperstepState {
        INITIAL, ///< Nothing happened yet
        WORKER_FAILURE, ///< A worker died during this superstep
        THIS_SUPERSTEP_DONE, ///< This superstep completed correctly
        ALL_SUPERSTEPS_DONE, ///< All supersteps are complete
    }

    /**
     * Become the master.
     * @return true if became the master, false if the application is done.
     */
    boolean becomeMaster();

    /**
     * Create the InputSplits from the index range based on the user-defined
     * VertexInputFormat.  These InputSplits will be split further into
     * partitions by the workers.
     *
     * @return number of partitions
     */
    int createInputSplits();

    /**
     * Master coordinates the superstep
     *
     * @return State of the application as a result of this superstep
     * @throws InterruptedException
     * @throws KeeperException
     */
    SuperstepState coordinateSuperstep()
        throws KeeperException, InterruptedException;

    /**
     * Master can decide to restart from the last good checkpoint if a
     * worker fails during a superstep.
     *
     * @param checkpoint Checkpoint to restart from
     */
    void restartFromCheckpoint(long checkpoint);

    /**
     * Get the last known good checkpoint
     * @throws IOException
     */
    long getLastGoodCheckpoint() throws IOException;

    /**
     * If the master decides that this job doesn't have the resources to
     * continue, it can fail the job.  It can also designate what to do next.
     * Typically this is mainly informative.
     *
     * @param state
     * @param applicationAttempt attempt to start on
     * @param desiredSuperstep Superstep to restart from (if applicable)
     */
    void setJobState(State state,
                     long applicationAttempt,
                     long desiredSuperstep);
}
