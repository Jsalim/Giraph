package com.yahoo.hadoop_bsp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.apache.commons.codec.binary.Base64;

import org.apache.log4j.Logger;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;

import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Mapper;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;

import com.yahoo.hadoop_bsp.BspJob.BspMapper.MapFunctions;

/**
 * Zookeeper-based implementation of {@link CentralizedService}.
 */
@SuppressWarnings("rawtypes")
public class BspServiceMaster<
        I extends WritableComparable,
        V extends Writable,
        E extends Writable, M extends Writable>
        extends BspService<I, V, E, M>
        implements CentralizedServiceMaster<I, V, E, M> {
    /** Class logger */
    private static final Logger LOG = Logger.getLogger(BspServiceMaster.class);
    /** Synchronizes vertex ranges */
    private Object m_vertexRangeSynchronization = new Object();
    /** Superstep counter */
    private Counter m_superstepCounter = null;
    /** Am I the master? */
    private boolean m_isMaster = false;
    /** Vertex range balancer class */
    private Class<? extends VertexRangeBalancer<I, V, E, M>>
        m_vertexRangeBalancerClass;
    /** Max number of workers */
    private final int m_maxWorkers;
    /** Min number of workers */
    private final int m_minWorkers;
    /** Min % responded workers */
    private final float m_minPercentResponded;
    /** Poll period in msecs */
    private final int m_msecsPollPeriod;
    /** Max number of poll attempts */
    private final int m_maxPollAttempts;
    /** Last finalized checkpoint */
    private long m_lastCheckpointedSuperstep = -1;
    /** State of the superstep changed */
    private final BspEvent m_superstepStateChanged =
        new PredicateLock();

    public BspServiceMaster(
            String serverPortList,
            int sessionMsecTimeout,
            Mapper<?, ?, ?, ?>.Context context,
            BspJob.BspMapper<I, V, E, M> bspMapper) {
        super(serverPortList, sessionMsecTimeout, context, bspMapper);
        registerBspEvent(m_superstepStateChanged);
        @SuppressWarnings("unchecked")
        Class<? extends VertexRangeBalancer<I, V, E, M>> vertexRangeBalancer =
            (Class<? extends VertexRangeBalancer<I, V, E, M>>)
            getConfiguration().getClass(BspJob.VERTEX_RANGE_BALANCER_CLASS,
                                        StaticBalancer.class,
                                        VertexRangeBalancer.class);
        m_vertexRangeBalancerClass = vertexRangeBalancer;

        m_maxWorkers =
            getConfiguration().getInt(BspJob.MAX_WORKERS, -1);
        m_minWorkers =
            getConfiguration().getInt(BspJob.MIN_WORKERS, -1);
        m_minPercentResponded =
            getConfiguration().getFloat(BspJob.MIN_PERCENT_RESPONDED,
                                        100.0f);
        m_msecsPollPeriod =
            getConfiguration().getInt(BspJob.POLL_MSECS,
                                      BspJob.POLL_MSECS_DEFAULT);
        m_maxPollAttempts =
            getConfiguration().getInt(BspJob.POLL_ATTEMPTS,
                                      BspJob.POLL_ATTEMPTS_DEFAULT);
    }

    @Override
    public void setJobState(State state,
                            long applicationAttempt,
                            long desiredSuperstep) {
        JSONObject jobState = new JSONObject();
        try {
            jobState.put(JSONOBJ_STATE_KEY, state.toString());
            jobState.put(JSONOBJ_APPLICATION_ATTEMPT_KEY, applicationAttempt);
            jobState.put(JSONOBJ_SUPERSTEP_KEY, desiredSuperstep);
        } catch (JSONException e) {
            throw new RuntimeException("setJobState: Coudn't put " +
                                       state.toString());
        }
        if (LOG.isInfoEnabled()) {
            LOG.info("setJobState: " + jobState.toString() + " on superstep " +
                     getSuperstep());
        }
        try {
            getZkExt().createExt(MASTER_JOB_STATE_PATH + "/jobState",
                                 jobState.toString().getBytes(),
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT_SEQUENTIAL,
                                 true);
        } catch (KeeperException.NodeExistsException e) {
            throw new RuntimeException(
                "setJobState: Imposible that " +
                MASTER_JOB_STATE_PATH + " already exists!", e);
        } catch (KeeperException e) {
            throw new RuntimeException(
                "setJobState: Unknown KeeperException for " +
                MASTER_JOB_STATE_PATH, e);
        } catch (InterruptedException e) {
            throw new RuntimeException(
                "setJobState: Unknown InterruptedException for " +
                MASTER_JOB_STATE_PATH, e);
        }

        if (state == BspService.State.FAILED) {
            failJob();
        }
    }

    /**
     * Master uses this to calculate the {@link VertexInputFormat}
     * input splits and write it to ZooKeeper.
     *
     * @param numSplits
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws IOException
     * @throws InterruptedException
     */
    private List<InputSplit> generateInputSplits(int numSplits) {
        try {
            @SuppressWarnings({"unchecked" })
            Class<VertexInputFormat<I, V, E>> vertexInputFormatClass =
                (Class<VertexInputFormat<I, V, E>>)
                 getConfiguration().getClass(BspJob.VERTEX_INPUT_FORMAT_CLASS,
                                             VertexInputFormat.class);
            List<InputSplit> splits =
                vertexInputFormatClass.newInstance().getSplits(
                    getConfiguration(), numSplits);
            return splits;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * When there is no salvaging this job, fail it.
     *
     * @throws IOException
     */
    private void failJob() {
        LOG.fatal("failJob: Killing job " + getJobId());
        try {
            @SuppressWarnings("deprecation")
            org.apache.hadoop.mapred.JobClient jobClient =
                new org.apache.hadoop.mapred.JobClient(
                    (org.apache.hadoop.mapred.JobConf)
                    getConfiguration());
            @SuppressWarnings("deprecation")
            org.apache.hadoop.mapred.JobID jobId =
                org.apache.hadoop.mapred.JobID.forName(getJobId());
            RunningJob job = jobClient.getJob(jobId);
            job.killJob();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the healthy and unhealthy workers for a superstep
     *
     * @param superstep superstep to check
     * @param healthyWorkerList filled in with current data
     * @param unhealthyWorkerList filled in with current data
     */
    private void getWorkers(long superstep,
                            List<String> healthyWorkerList,
                            List<String> unhealthyWorkerList) {
        String healthyWorkerPath =
            getWorkerHealthyPath(getApplicationAttempt(), superstep);
        String unhealthyWorkerPath =
            getWorkerUnhealthyPath(getApplicationAttempt(), superstep);

        try {
            getZkExt().createExt(healthyWorkerPath,
                                 null,
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT,
                                 true);
        } catch (KeeperException.NodeExistsException e) {
            LOG.debug("getWorkers: " + healthyWorkerPath +
                      " already exists, no need to create.");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            getZkExt().createExt(unhealthyWorkerPath,
                                 null,
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT,
                                 true);
        } catch (KeeperException.NodeExistsException e) {
            LOG.info("getWorkers: " + healthyWorkerPath +
                     " already exists, no need to create.");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<String> currentHealthyWorkerList = null;
        List<String> currentUnhealthyWorkerList = null;
        try {
            currentHealthyWorkerList =
                getZkExt().getChildrenExt(healthyWorkerPath, true, false, false);
        } catch (KeeperException.NoNodeException e) {
            LOG.info("getWorkers: No node for " + healthyWorkerPath);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            currentUnhealthyWorkerList =
                getZkExt().getChildrenExt(unhealthyWorkerPath, true, false, false);
        } catch (KeeperException.NoNodeException e) {
            LOG.info("getWorkers: No node for " + unhealthyWorkerPath);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        healthyWorkerList.clear();
        for (String healthyWorker : currentHealthyWorkerList) {
            healthyWorkerList.add(healthyWorker);
        }

        unhealthyWorkerList.clear();
        for (String unhealthyWorker : currentUnhealthyWorkerList) {
            unhealthyWorkerList.add(unhealthyWorker);
        }
    }

    /**
     * Check the workers to ensure that a minimum number of good workers exists
     * out of the total that have reported.
     *
     * @return map of healthy worker list to JSONArray(hostname, port)
     */
    private Map<String, JSONArray> checkWorkers() {
        boolean failJob = true;
        int pollAttempt = 0;
        List<String> healthyWorkerList = new ArrayList<String>();
        List<String> unhealthyWorkerList = new ArrayList<String>();
        int totalResponses = -1;
        while (pollAttempt < m_maxPollAttempts) {
            getWorkers(
                getSuperstep(), healthyWorkerList, unhealthyWorkerList);
            totalResponses = healthyWorkerList.size() +
                unhealthyWorkerList.size();
            if ((totalResponses * 100.0f / m_maxWorkers) >=
                m_minPercentResponded) {
                failJob = false;
                break;
            }
            LOG.info("checkWorkers: Only found " + totalResponses +
                     " responses of " + m_maxWorkers + " for superstep " +
                     getSuperstep() + ".  Sleeping for " +
                     m_msecsPollPeriod + " msecs and used " + pollAttempt +
                     " of " + m_maxPollAttempts + " attempts.");
            if (getWorkerHealthRegistrationChangedEvent().waitMsecs(
                    m_msecsPollPeriod)) {
                LOG.info("checkWorkers: Got event that health " +
                         "registration changed, not using poll attempt");
                getWorkerHealthRegistrationChangedEvent().reset();
                continue;
            }
            ++pollAttempt;
        }
        if (failJob) {
            LOG.warn("checkWorkers: Did not receive enough processes in " +
                     "time (only " + totalResponses + " of " +
                     m_minWorkers + " required)");
            return null;
        }

        if (healthyWorkerList.size() < m_minWorkers) {
            LOG.warn("checkWorkers: Only " + healthyWorkerList.size() +
                     " available when " + m_minWorkers + " are required.");
            return null;
        }

        Map<String, JSONArray> workerHostnamePortMap =
            new HashMap<String, JSONArray>();
        for (String healthyWorker: healthyWorkerList) {
            String healthyWorkerPath = null;
            try {
                healthyWorkerPath =
                    getWorkerHealthyPath(getApplicationAttempt(),
                                         getSuperstep()) + "/" +  healthyWorker;
                JSONArray hostnamePortArray =
                    new JSONArray(
                        new String(getZkExt().getData(healthyWorkerPath,
                                                      false,
                                                      null)));
                workerHostnamePortMap.put(healthyWorker, hostnamePortArray);
            } catch (Exception e) {
                throw new RuntimeException(
                    "checkWorkers: Problem fetching hostname and port for " +
                    healthyWorker + " in " + healthyWorkerPath);
            }
        }

        return workerHostnamePortMap;
    }

    public int createInputSplits() {
        // Only the 'master' should be doing this.  Wait until the number of
        // processes that have reported health exceeds the minimum percentage.
        // If the minimum percentage is not met, fail the job.  Otherwise
        // generate the input splits
        try {
            if (getZkExt().exists(INPUT_SPLIT_PATH, false) != null) {
                LOG.info(INPUT_SPLIT_PATH +
                         " already exists, no need to create");
                return Integer.parseInt(
                    new String(
                        getZkExt().getData(INPUT_SPLIT_PATH, false, null)));
            }
        } catch (KeeperException.NoNodeException e) {
            LOG.info("createInputSplits: Need to create the " +
                     "input splits at " + INPUT_SPLIT_PATH);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // When creating znodes, in case the master has already run, resume
        // where it left off.
        Map<String, JSONArray> healthyWorkerHostnamePortMap = checkWorkers();
        if (healthyWorkerHostnamePortMap == null) {
            setJobState(State.FAILED, -1, -1);
        }

        List<InputSplit> splitList =
            generateInputSplits(healthyWorkerHostnamePortMap.size());
        if (healthyWorkerHostnamePortMap.size() > splitList.size()) {
            LOG.warn("createInputSplits: Number of inputSplits="
                     + splitList.size() + " < " +
                     healthyWorkerHostnamePortMap.size() +
                     "=number of healthy processes");
        }
        else if (healthyWorkerHostnamePortMap.size() < splitList.size()) {
            LOG.fatal("masterGenerateInputSplits: Number of inputSplits="
                      + splitList.size() + " > " +
                      healthyWorkerHostnamePortMap.size() +
                      "=number of healthy processes");
            setJobState(State.FAILED, -1, -1);
        }
        String inputSplitPath = null;
        for (int i = 0; i< splitList.size(); ++i) {
            try {
                ByteArrayOutputStream byteArrayOutputStream =
                    new ByteArrayOutputStream();
                DataOutput outputStream =
                    new DataOutputStream(byteArrayOutputStream);
                InputSplit inputSplit = splitList.get(i);
                Text.writeString(outputStream,
                                 inputSplit.getClass().getName());
                ((Writable) inputSplit).write(outputStream);
                inputSplitPath = INPUT_SPLIT_PATH + "/" + i;
                getZkExt().createExt(inputSplitPath,
                                     byteArrayOutputStream.toByteArray(),
                                     Ids.OPEN_ACL_UNSAFE,
                                     CreateMode.PERSISTENT,
                                     true);
                LOG.debug("createInputSplits: Created input split with index " +
                         i + " serialized as " +
                         byteArrayOutputStream.toString());
            } catch (KeeperException.NodeExistsException e) {
                LOG.info("createInputSplits: Node " +
                         inputSplitPath + " already exists.");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        // Let workers know they can start trying to load the input splits
        try {
            getZkExt().create(INPUT_SPLITS_ALL_READY_PATH,
                        null,
                        Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException e) {
            LOG.info("createInputSplits: Node " +
                     INPUT_SPLITS_ALL_READY_PATH + " already exists.");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return splitList.size();
    }

    /**
     * Read the finalized checkpoint file and associated metadata files for the
     * checkpoint.  Assign one worker per checkpoint point (and associated
     * vertex ranges).  Remove any unused chosen workers
     * (this is safe since it wasn't finalized).  The workers can then
     * find the vertex ranges within the files.  It is an
     * optimization to prevent all workers from searching all the files.
     * Also read in the aggregator data from the finalized checkpoint
     * file.
     *
     * @param superstep checkpoint set to examine.
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    private void mapFilesToWorkers(long superstep,
                                   List<String> chosenWorkerList)
            throws IOException, KeeperException, InterruptedException {
        FileSystem fs = getFs();
        List<Path> validMetadataPathList = new ArrayList<Path>();
        String finalizedCheckpointPath =
            getCheckpointBasePath(superstep) + CHECKPOINT_FINALIZED_POSTFIX;
        DataInputStream finalizedStream =
            fs.open(new Path(finalizedCheckpointPath));
        int prefixFileCount = finalizedStream.readInt();
        for (int i = 0; i < prefixFileCount; ++i) {
            String metadataFilePath =
                finalizedStream.readUTF() + CHECKPOINT_METADATA_POSTFIX;
            validMetadataPathList.add(new Path(metadataFilePath));
        }

        // Set the merged aggregator data if it exists.
        int aggregatorDataSize = finalizedStream.readInt();
        if (aggregatorDataSize > 0) {
            byte [] aggregatorZkData = new byte[aggregatorDataSize];
            int actualDataRead =
                finalizedStream.read(aggregatorZkData, 0, aggregatorDataSize);
            if (actualDataRead != aggregatorDataSize) {
                throw new RuntimeException(
                    "mapFilesToWorkers: Only read " + actualDataRead + " of " +
                    aggregatorDataSize + " aggregator bytes from " +
                    finalizedCheckpointPath);
            }
            String mergedAggregatorPath =
                getMergedAggregatorPath(getApplicationAttempt(), superstep - 1);
            if (LOG.isInfoEnabled()) {
                LOG.info("mapFilesToWorkers: Reloading merged aggregator " +
                         "data '" + aggregatorZkData +
                         "' to previous checkpoint in path " +
                         mergedAggregatorPath);
            }
            if (getZkExt().exists(mergedAggregatorPath, false) == null) {
                getZkExt().createExt(mergedAggregatorPath,
                                     aggregatorZkData,
                                     Ids.OPEN_ACL_UNSAFE,
                                     CreateMode.PERSISTENT,
                                     true);
            }
            else {
                getZkExt().setData(mergedAggregatorPath, aggregatorZkData, -1);
            }
        }
        finalizedStream.close();

        Map<String, Set<String>> workerFileSetMap =
            new HashMap<String, Set<String>>();
        // Reading the metadata files.  For now, implement simple algorithm:
        // 1. Every file is set an input split and the partitions are mapped
        //    accordingly
        // 2. Every worker gets a hint about which files to look in to find
        //    the input splits
        int chosenWorkerListIndex = 0;
        int inputSplitIndex = 0;
        I maxVertexIndex = BspUtils.<I>createVertexIndex(getConfiguration());
        for (Path metadataPath : validMetadataPathList) {
            String checkpointFilePrefix = metadataPath.toString();
            checkpointFilePrefix =
                checkpointFilePrefix.substring(
                0,
                checkpointFilePrefix.length() -
                CHECKPOINT_METADATA_POSTFIX.length());
            DataInputStream metadataStream = fs.open(metadataPath);
            long entries = metadataStream.readLong();
            JSONArray vertexRangeMetaArray = new JSONArray();
            JSONArray vertexRangeArray = new JSONArray();
            String chosenWorker =
                chosenWorkerList.get(chosenWorkerListIndex);
            for (long i = 0; i < entries; ++i) {
                long dataPos = metadataStream.readLong();
                Long vertexCount = metadataStream.readLong();
                Long edgeCount = metadataStream.readLong();
                maxVertexIndex.readFields(metadataStream);
                LOG.debug("mapFileToWorkers: File " + metadataPath +
                          " with position " + dataPos + ", vertex count " +
                          vertexCount + " assigned to " + chosenWorker);
                ByteArrayOutputStream outputStream =
                    new ByteArrayOutputStream();
                DataOutput output = new DataOutputStream(outputStream);
                maxVertexIndex.write(output);

                JSONObject vertexRangeObj = new JSONObject();
                try {
                    vertexRangeObj.put(JSONOBJ_NUM_VERTICES_KEY,
                                       vertexCount);
                    vertexRangeObj.put(JSONOBJ_NUM_EDGES_KEY,
                                       edgeCount);
                    vertexRangeObj.put(JSONOBJ_HOSTNAME_ID_KEY,
                                       chosenWorker);
                    vertexRangeObj.put(JSONOBJ_CHECKPOINT_FILE_PREFIX_KEY,
                                       checkpointFilePrefix);
                    vertexRangeObj.put(JSONOBJ_MAX_VERTEX_INDEX_KEY,
                                       outputStream.toString("UTF-8"));
                    vertexRangeMetaArray.put(vertexRangeObj);
                    vertexRangeArray.put(outputStream.toString("UTF-8"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                if (!workerFileSetMap.containsKey(chosenWorker)) {
                    workerFileSetMap.put(chosenWorker, new HashSet<String>());
                }
                Set<String> fileSet = workerFileSetMap.get(chosenWorker);
                fileSet.add(metadataPath.toString());
            }
            metadataStream.close();

            String inputSplitPathFinishedPath =
                INPUT_SPLIT_PATH + "/" + inputSplitIndex +
                INPUT_SPLIT_FINISHED_NODE;
            LOG.debug("mapFilesToWorkers: Assigning (if not empty) " +
                     chosenWorker + " the following vertexRanges: " +
                     vertexRangeArray.toString());

            // Assign the input splits if there is at least one vertex range
            if (vertexRangeArray.length() == 0) {
                continue;
            }
            try {
                LOG.info("mapFilesToWorkers: vertexRangeMetaArray size=" +
                         vertexRangeMetaArray.toString().length());
                 getZkExt().createExt(inputSplitPathFinishedPath,
                                     vertexRangeMetaArray.toString().getBytes(),
                                     Ids.OPEN_ACL_UNSAFE,
                                     CreateMode.PERSISTENT,
                                     true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            ++inputSplitIndex;
            ++chosenWorkerListIndex;
            if (chosenWorkerListIndex == chosenWorkerList.size()) {
                chosenWorkerListIndex = 0;
            }
        }
    }

    public void setup() {
        // Might have to manually load a checkpoint.
        // In that case, the input splits are not set, they will be faked by
        // the checkpoint files.  Each checkpoint file will be an input split
        // and the input split
        m_superstepCounter = getContext().getCounter("BspJob", "Superstep");
        if (getRestartedSuperstep() == -1) {
            return;
        }
        else if (getRestartedSuperstep() < -1) {
            LOG.fatal("setup: Impossible to restart superstep " +
                      getRestartedSuperstep());
            setJobState(BspService.State.FAILED, -1, -1);
        } else {
            m_superstepCounter.increment(getRestartedSuperstep());
        }

    }

    public boolean becomeMaster() {
        // Create my bid to become the master, then try to become the worker
        // or return false.
        String myBid = null;
        try {
            myBid =
                getZkExt().createExt(MASTER_ELECTION_PATH +
                    "/" + getHostnamePartitionId(),
                    null,
                    Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL_SEQUENTIAL,
                    true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        while (true) {
            JSONObject jobState = getJobState();
            try {
                if ((jobState != null) &&
                    State.valueOf(jobState.getString(JSONOBJ_STATE_KEY)) ==
                        State.FINISHED) {
                    LOG.info("becomeMaster: Job is finished, " +
                             "give up trying to be the master!");
                    m_isMaster = false;
                    return m_isMaster;
                }
            } catch (JSONException e) {
                throw new RuntimeException(
                    "becomeMaster: Couldn't get state from " + jobState, e);
            }
            try {
                List<String> masterChildArr =
                    getZkExt().getChildrenExt(
                        MASTER_ELECTION_PATH, true, true, true);
                LOG.info("becomeMaster: First child is '" +
                         masterChildArr.get(0) + "' and my bid is '" +
                         myBid + "'");
                if (masterChildArr.get(0).equals(myBid)) {
                    LOG.info("becomeMaster: I am now the master!");
                    m_isMaster = true;
                    return m_isMaster;
                }
                LOG.info("becomeMaster: Waiting to become the master...");
                getMasterElectionChildrenChangedEvent().waitForever();
                getMasterElectionChildrenChangedEvent().reset();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Get the vertex range statistics for a particular superstep.
     *
     * @param superstep superstep to check
     * @return mapping of max index (vertex range) to JSONObject
     *         (# done, # total)
     */
    private Map<I, JSONObject> collectVertexRangeStats(long superstep) {
        Map<I, JSONObject> vertexRangeStatArrayMap =
            new TreeMap<I, JSONObject>();

        // Superstep 0 is special since there is no computation, just get
        // the stats from the input splits finished nodes.  Otherwise, get the
        // stats from the all the worker selected nodes
        if (superstep == 0) {
            List<String> inputSplitList = null;
            try {
                inputSplitList = getZkExt().getChildrenExt(
                    INPUT_SPLIT_PATH, false, false, true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            for (String inputSplitPath : inputSplitList) {
                JSONArray statArray = null;
                try {
                    String inputSplitFinishedPath = inputSplitPath +
                        INPUT_SPLIT_FINISHED_NODE;
                    byte [] zkData =
                        getZkExt().getData(inputSplitFinishedPath, false, null);
                    if (zkData == null || zkData.length == 0) {
                        continue;
                    }
                    if (LOG.isInfoEnabled()) {
                        LOG.info("collectVertexRangeStats: input split path " +
                                 inputSplitPath + " got " + zkData);
                    }
                    statArray = new JSONArray(new String(zkData));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                for (int i = 0; i < statArray.length(); ++i) {
                    try {
                        I maxVertexIndex =
                            BspUtils.<I>createVertexIndex(getConfiguration());
                        InputStream input =
                            new ByteArrayInputStream(
                                statArray.getJSONObject(i).getString(
                                    JSONOBJ_MAX_VERTEX_INDEX_KEY).getBytes("UTF-8"));
                        ((Writable) maxVertexIndex).readFields(
                            new DataInputStream(input));
                        statArray.getJSONObject(i).put(
                            JSONOBJ_FINISHED_VERTICES_KEY,
                            0);
                        vertexRangeStatArrayMap.put(maxVertexIndex,
                                                    statArray.getJSONObject(i));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        else {
            String workerFinishedPath =
                getWorkerFinishedPath(getApplicationAttempt(), superstep);
            List<String> workerFinishedPathList = null;
            try {
                workerFinishedPathList =
                    getZkExt().getChildrenExt(
                        workerFinishedPath, false, false, true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            for (String finishedPath : workerFinishedPathList) {
                JSONObject aggregatorStatObj= null;
                try {
                    byte [] zkData =
                        getZkExt().getData(finishedPath, false, null);
                    aggregatorStatObj = new JSONObject(new String(zkData));

                    JSONArray statArray =
                        aggregatorStatObj.getJSONArray(
                            JSONOBJ_VERTEX_RANGE_STAT_ARRAY_KEY);
                    for (int i = 0; i < statArray.length(); ++i) {
                        I maxVertexIndex =
                            BspUtils.<I>createVertexIndex(getConfiguration());
                        InputStream input =
                            new ByteArrayInputStream(
                                statArray.getJSONObject(i).getString(
                                    JSONOBJ_MAX_VERTEX_INDEX_KEY).getBytes(
                                        "UTF-8"));
                        ((Writable) maxVertexIndex).readFields(
                            new DataInputStream(input));
                        vertexRangeStatArrayMap.put(maxVertexIndex,
                                                    statArray.getJSONObject(i));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
         }

        return vertexRangeStatArrayMap;
    }

    /**
     * Get the aggregator values for a particular superstep,
     * aggregate and save them.
     *
     * @param superstep superstep to check
     */
    private void collectAndProcessAggregatorValues(long superstep) {
        if (superstep <= 0) {
            return;
        }
        Map<String, Aggregator<? extends Writable>> aggregatorMap =
            new TreeMap<String, Aggregator<? extends Writable>>();
        String workerFinishedPath =
            getWorkerFinishedPath(getApplicationAttempt(), superstep);
        List<String> hostnameIdPathList = null;
        try {
            hostnameIdPathList =
                getZkExt().getChildrenExt(
                    workerFinishedPath, false, false, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Base64 base64 = new Base64();
        for (String hostnameIdPath : hostnameIdPathList) {
            JSONObject aggregatorsStatObj = null;
            JSONArray aggregatorArray = null;
            try {
                byte [] zkData =
                    getZkExt().getData(hostnameIdPath, false, null);
                aggregatorsStatObj = new JSONObject(new String(zkData));
            } catch (Exception e) {
               throw new RuntimeException(
                         "collectAndProcessAggregatorValues: " +
                         "exception when fetching data from " +
                         hostnameIdPath, e);
            }
            try {
                aggregatorArray = aggregatorsStatObj.getJSONArray(
                    JSONOBJ_AGGREGATOR_VALUE_ARRAY_KEY);
            } catch (JSONException e) {
                LOG.debug("collectAndProcessAggregatorValues: No aggregators" +
                          " for " + hostnameIdPath);
                continue;
            }
            for (int i = 0; i < aggregatorArray.length(); ++i) {
                try {
                    LOG.info("collectAndProcessAggregatorValues: " +
                             "Getting aggregators from " +
                              aggregatorArray.getJSONObject(i));
                    String aggregatorName =
                        aggregatorArray.getJSONObject(i).getString(
                            AGGREGATOR_NAME_KEY);
                    String aggregatorClassName =
                        aggregatorArray.getJSONObject(i).getString(
                            AGGREGATOR_CLASS_NAME_KEY);
                    @SuppressWarnings("unchecked")
                    Aggregator<Writable> aggregator =
                        (Aggregator<Writable>) aggregatorMap.get(aggregatorName);
                    boolean firstTime = false;
                    if (aggregator == null) {
                        @SuppressWarnings("unchecked")
                        Aggregator<Writable> aggregatorWritable =
                            (Aggregator<Writable>) getAggregator(aggregatorName);
                        aggregator = aggregatorWritable;
                        if (aggregator == null) {
                            @SuppressWarnings("unchecked")
                            Class<? extends Aggregator<Writable>> aggregatorClass =
                                (Class<? extends Aggregator<Writable>>)
                                    Class.forName(aggregatorClassName);
                            aggregator = registerAggregator(
                                aggregatorName,
                                aggregatorClass);
                        }
                        aggregatorMap.put(aggregatorName, aggregator);
                        firstTime = true;
                    }
                    Writable aggregatorValue =
                        aggregator.createAggregatedValue();
                    InputStream input =
                        new ByteArrayInputStream(
                            (byte[]) base64.decode(
                                aggregatorArray.getJSONObject(i).
                                getString(AGGREGATOR_VALUE_KEY)));
                    aggregatorValue.readFields(new DataInputStream(input));
                    LOG.debug("collectAndProcessAggregatorValues: aggregator " +
                              "value size=" + input.available() +
                              " for aggregator=" + aggregatorName +
                              " value=" + aggregatorValue);
                    if (firstTime) {
                        aggregator.setAggregatedValue(aggregatorValue);
                    } else {
                        aggregator.aggregate(aggregatorValue);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(
                             "collectAndProcessAggregatorValues: " +
                             "exception when reading aggregator data " +
                             aggregatorArray, e);
                }
            }
        }
        if (aggregatorMap.size() > 0) {
            String mergedAggregatorPath =
                getMergedAggregatorPath(getApplicationAttempt(), superstep);
            byte [] zkData = null;
            JSONArray aggregatorArray = new JSONArray();
            for (Map.Entry<String, Aggregator<? extends Writable>> entry :
                    aggregatorMap.entrySet()) {
                try {
                    ByteArrayOutputStream outputStream =
                        new ByteArrayOutputStream();
                    DataOutput output = new DataOutputStream(outputStream);
                    entry.getValue().getAggregatedValue().write(output);

                    JSONObject aggregatorObj = new JSONObject();
                    aggregatorObj.put(AGGREGATOR_NAME_KEY,
                                      entry.getKey());
                    aggregatorObj.put(
                        AGGREGATOR_VALUE_KEY,
                        base64.encodeToString(outputStream.toByteArray()));
                    aggregatorArray.put(aggregatorObj);
                    LOG.info("collectAndProcessAggregatorValues: " +
                             "Trying to add aggregatorObj " +
                             aggregatorObj + "(" +
                             entry.getValue().getAggregatedValue() +
                             ") to merged aggregator path " +
                             mergedAggregatorPath);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            try {
                zkData = aggregatorArray.toString().getBytes();
                getZkExt().createExt(mergedAggregatorPath,
                                     zkData,
                                     Ids.OPEN_ACL_UNSAFE,
                                     CreateMode.PERSISTENT,
                                     true);
            } catch (KeeperException.NodeExistsException e) {
                LOG.warn("collectAndProcessAggregatorValues: " +
                         mergedAggregatorPath+
                         " already exists!");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            LOG.info("collectAndProcessAggregatorValues: Finished loading " +
                     mergedAggregatorPath+ " with aggregator values " +
                     aggregatorArray);
        }
    }

    /**
     * Finalize the checkpoint file prefixes by taking the chosen workers and
     * writing them to a finalized file.  Also write out the master
     * aggregated aggregator array from the previous superstep.
     *
     * @param superstep superstep to finalize
     * @param chosenWorkerList list of chosen workers that will be finalized
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    private void finalizeCheckpoint(
            long superstep,
            List<String> chosenWorkerList)
            throws IOException, KeeperException, InterruptedException {
        Path finalizedCheckpointPath =
            new Path(getCheckpointBasePath(superstep) +
                     CHECKPOINT_FINALIZED_POSTFIX);
        try {
            getFs().delete(finalizedCheckpointPath, false);
        } catch (IOException e) {
            LOG.warn("finalizedValidCheckpointPrefixes: Removed old file " +
                     finalizedCheckpointPath);
        }

        // Format:
        // <number of files>
        // <used file prefix 0><used file prefix 1>...
        // <aggregator data length><aggregators as a serialized JSON byte array>
        FSDataOutputStream finalizedOutputStream =
            getFs().create(finalizedCheckpointPath);
        finalizedOutputStream.writeInt(chosenWorkerList.size());
        for (String chosenWorker : chosenWorkerList) {
            String chosenWorkerPrefix =
                getCheckpointBasePath(superstep) + "." + chosenWorker;
            finalizedOutputStream.writeUTF(chosenWorkerPrefix);
        }
        String mergedAggregatorPath =
            getMergedAggregatorPath(getApplicationAttempt(), superstep - 1);
        if (getZkExt().exists(mergedAggregatorPath, false) != null) {
            byte [] aggregatorZkData =
                getZkExt().getData(mergedAggregatorPath, false, null);
            finalizedOutputStream.writeInt(aggregatorZkData.length);
            finalizedOutputStream.write(aggregatorZkData);
        }
        else {
            finalizedOutputStream.writeInt(0);
        }
        finalizedOutputStream.close();
        m_lastCheckpointedSuperstep = superstep;
    }

    /**
     * Convert the processed input split data into vertex range assignments
     * that can be used on the next superstep.
     *
     * @param chosenWorkerHostnamePortMap map of chosen workers to
     *        a hostname and port array
     */
    private void inputSplitsToVertexRanges(
            Map<String, JSONArray> chosenWorkerHostnamePortMap) {
        List<String> inputSplitPathList = null;
        List<VertexRange<I, V, E, M>> vertexRangeList =
            new ArrayList<VertexRange<I, V, E, M>>();
        try {
            inputSplitPathList = getZkExt().getChildrenExt(INPUT_SPLIT_PATH,
                                                           false,
                                                           false,
                                                           true);
            int numRanges = 0;
            for (String inputSplitPath : inputSplitPathList) {
                String inputSplitFinishedPath = inputSplitPath +
                    INPUT_SPLIT_FINISHED_NODE;
                byte [] zkData =
                    getZkExt().getData(inputSplitFinishedPath, false, null);
                if (zkData == null || zkData.length == 0) {
                    LOG.info("inputSplitsToVertexRanges: No vertex ranges " +
                             "in " + inputSplitFinishedPath);
                    continue;
                }
                JSONArray statArray = new JSONArray(new String(zkData));
                JSONObject vertexRangeObj = null;
                for (int i = 0; i < statArray.length(); ++i) {
                    vertexRangeObj = statArray.getJSONObject(i);
                    String hostnamePartitionId =
                        vertexRangeObj.getString(JSONOBJ_HOSTNAME_ID_KEY);
                    if (!chosenWorkerHostnamePortMap.containsKey(
                        hostnamePartitionId)) {
                        LOG.fatal("inputSplitsToVertexRanges: Worker " +
                              hostnamePartitionId + " died, failing.");
                        setJobState(State.FAILED, -1, -1);
                    }
                   JSONArray hostnamePortArray =
                       chosenWorkerHostnamePortMap.get(hostnamePartitionId);
                   vertexRangeObj.put(JSONOBJ_HOSTNAME_KEY,
                                      hostnamePortArray.getString(0));
                   vertexRangeObj.put(JSONOBJ_PORT_KEY,
                                      hostnamePortArray.getString(1));
                   Class<I> indexClass =
                       BspUtils.getVertexIndexClass(getConfiguration());
                    VertexRange<I, V, E, M> vertexRange =
                        new VertexRange<I, V, E, M>(indexClass, vertexRangeObj);
                    vertexRangeList.add(vertexRange);
                    numRanges++;
                }
            }

            JSONArray vertexRangeAssignmentArray =
                new JSONArray();
            for (VertexRange<I, V, E, M> vertexRange : vertexRangeList) {
                vertexRangeAssignmentArray.put(vertexRange.toJSONObject());
            }
            String vertexRangeAssignmentsPath =
                getVertexRangeAssignmentsPath(getApplicationAttempt(),
                                              getSuperstep());
            LOG.info("inputSplitsToVertexRanges: Assigning " + numRanges +
                     " vertex ranges of total length " +
                     vertexRangeAssignmentArray.toString().length() +
                     " to path " + vertexRangeAssignmentsPath);
            getZkExt().createExt(vertexRangeAssignmentsPath,
                                 vertexRangeAssignmentArray.toString().getBytes(),
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT,
                                 true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Balance the vertex ranges before the next superstep computation begins.
     * Wait until the vertex ranges data has been moved to the correct
     * worker prior to continuing.
     *
     * @param vertexRangeBalancer balancer to use
     * @param chosenWorkerHostnamePortMap workers available
     * @throws JSONException
     * @throws IOException
     * @throws UnsupportedEncodingException
     */
    private void balanceVertexRanges(
            BspBalancer<I, V, E, M> vertexRangeBalancer,
            Map<String, JSONArray> chosenWorkerHostnamePortMap) {
        vertexRangeBalancer.setSuperstep(getSuperstep());
        vertexRangeBalancer.setWorkerHostnamePortMap(
            chosenWorkerHostnamePortMap);
        NavigableMap<I, VertexRange<I, V, E, M>> vertexRangeMap =
            getVertexRangeMap(getSuperstep() - 1);
        vertexRangeBalancer.setPrevVertexRangeMap(vertexRangeMap);
        vertexRangeBalancer.rebalance();
        vertexRangeBalancer.setPreviousHostnamePort();
        NavigableMap<I, VertexRange<I, V, E, M>> nextVertexRangeMap =
            vertexRangeBalancer.getNextVertexRangeMap();
        if (nextVertexRangeMap.size() != vertexRangeMap.size()) {
            throw new RuntimeException(
                "balanceVertexRanges: Next vertex range count " +
                nextVertexRangeMap.size() + " != " + vertexRangeMap.size());
        }
        JSONArray vertexRangeAssignmentArray = new JSONArray();
        VertexRange<I, V, E, M> maxVertexRange = null; // TODO: delete after Bug 4340282 fixed.
        for (VertexRange<I, V, E, M> vertexRange :
                nextVertexRangeMap.values()) {
            try {
                vertexRangeAssignmentArray.put(
                    vertexRange.toJSONObject());
                if (maxVertexRange == null || // TODO: delete after Bug 4340282 fixed.
                        vertexRange.toJSONObject().toString().length() <
                        maxVertexRange.toJSONObject().toString().length()) {
                    maxVertexRange = vertexRange;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (vertexRangeAssignmentArray.length() == 0) {
            throw new RuntimeException(
                "balanceVertexRanges: Impossible there are no vertex ranges ");
        }

        byte[] vertexAssignmentBytes = null;
        try {
            vertexAssignmentBytes =
                vertexRangeAssignmentArray.toString().getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(
                "balanceVertexranges: Cannot get bytes from " +
                vertexRangeAssignmentArray.toString(), e);
        }

        // TODO: delete after Bug 4340282 fixed.
        if (LOG.isInfoEnabled()) {
            try {
                LOG.info("balanceVertexRanges: numVertexRanges=" +
                         nextVertexRangeMap.values().size() +
                         " lengthVertexRanges=" + vertexAssignmentBytes.length +
                         " maxVertexRangeLength=" +
                         maxVertexRange.toJSONObject().toString().length());
            } catch (IOException e) {
                throw new RuntimeException(
                    "balanceVertexRanges: IOException fetching " +
                    maxVertexRange.toString(), e);
            } catch (JSONException e) {
                throw new RuntimeException(
                    "balanceVertexRanges: JSONException fetching " +
                    maxVertexRange.toString(), e);
            }
        }

        String vertexRangeAssignmentsPath =
            getVertexRangeAssignmentsPath(getApplicationAttempt(),
                                          getSuperstep());
        try {
            getZkExt().createExt(
                                 vertexRangeAssignmentsPath,
                                 vertexAssignmentBytes,
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT,
                                 true);
        } catch (KeeperException.NodeExistsException e) {
            LOG.info("balanceVertexRanges: Node " +
                     vertexRangeAssignmentsPath + " already exists", e);
        } catch (KeeperException e) {
            LOG.fatal("balanceVertexRanges: Got KeeperException", e);
            throw new RuntimeException(
                "balanceVertexRanges: Got KeeperException", e);
        } catch (InterruptedException e) {
            LOG.fatal("balanceVertexRanges: Got KeeperException", e);
            throw new RuntimeException(
                "balanceVertexRanges: Got InterruptedException", e);
        }

        long changes = vertexRangeBalancer.getVertexRangeChanges();
        LOG.info("balanceVertexRanges: Waiting on " + changes +
                 " vertex range changes");
        if (changes == 0) {
            return;
        }

        String vertexRangeExchangePath =
            getVertexRangeExchangePath(getApplicationAttempt(), getSuperstep());
        List<String> workerExchangeList = null;
        try {
            getZkExt().createExt(vertexRangeExchangePath,
                                 null,
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT,
                                 true);
        } catch (KeeperException.NodeExistsException e) {
            LOG.info("balanceVertexRanges: " + vertexRangeExchangePath +
                     "exists");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        while (true) {
            try {
                workerExchangeList =
                    getZkExt().getChildrenExt(
                        vertexRangeExchangePath, true, false, false);
            } catch (KeeperException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (workerExchangeList.size() == changes) {
                break;
            }
            getVertexRangeExchangeChildrenChangedEvent().waitForever();
            getVertexRangeExchangeChildrenChangedEvent().reset();
        }
        String vertexRangeExchangeFinishedPath =
            getVertexRangeExchangeFinishedPath(getApplicationAttempt(),
                                               getSuperstep());
        try {
            getZkExt().createExt(vertexRangeExchangeFinishedPath,
                                 null,
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT,
                                 true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check whether the workers chosen for this superstep are still alive
     *
     * @param chosenWorkerHealthPath Path to the healthy workers in ZooKeeper
     * @param chosenWorkerSet Set of the healthy workers
     * @return true if they are all alive, false otherwise.
     * @throws InterruptedException
     * @throws KeeperException
     */
    private boolean superstepChosenWorkerAlive(
            String chosenWorkerHealthPath,
            Set<String> chosenWorkerSet)
            throws KeeperException, InterruptedException {
        List<String> chosenWorkerHealthyList =
            getZkExt().getChildrenExt(chosenWorkerHealthPath,
                                      true,
                                      false,
                                      false);
        Set<String> chosenWorkerHealthySet =
            new HashSet<String>(chosenWorkerHealthyList);
        boolean allChosenWorkersHealthy = true;
        for (String chosenWorker : chosenWorkerSet) {
            if (!chosenWorkerHealthySet.contains(chosenWorker)) {
                allChosenWorkersHealthy = false;
                LOG.warn("superstepChosenWorkerAlive: Missing chosen worker " +
                         chosenWorker + " on superstep " + getSuperstep());
            }
        }
        return allChosenWorkersHealthy;
    }

    @Override
    public void restartFromCheckpoint(long checkpoint) {
        // Process:
        // 1. Remove all old input split data
        // 2. Increase the application attempt and set to the correct checkpoint
        // 3. Send command to all workers to restart their tasks
        try {
            getZkExt().deleteExt(INPUT_SPLIT_PATH, -1, true);
        } catch (InterruptedException e) {
            throw new RuntimeException(
                "retartFromCheckpoint: InterruptedException", e);
        } catch (KeeperException e) {
            throw new RuntimeException(
                "retartFromCheckpoint: KeeperException", e);
        }
        setApplicationAttempt(getApplicationAttempt() + 1);
        setCachedSuperstep(checkpoint);
        setRestartedSuperstep(checkpoint);
        setJobState(BspService.State.START_SUPERSTEP,
                    getApplicationAttempt(),
                    checkpoint);
    }

    /**
     * Only get the finalized checkpoint files
     */
    public static class FinalizedCheckpointPathFilter implements PathFilter {
        @Override
        public boolean accept(Path path) {
            if (path.getName().endsWith(
                    BspService.CHECKPOINT_FINALIZED_POSTFIX)) {
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public long getLastGoodCheckpoint() throws IOException {
        // Find the last good checkpoint if none have been written to the
        // knowledge of this master
        if (m_lastCheckpointedSuperstep == -1) {
            FileStatus[] fileStatusArray =
                getFs().listStatus(new Path(CHECKPOINT_BASE_PATH),
                                   new FinalizedCheckpointPathFilter());
            if (fileStatusArray == null) {
                return -1;
            }
            Arrays.sort(fileStatusArray);
            m_lastCheckpointedSuperstep = getCheckpoint(
                fileStatusArray[fileStatusArray.length - 1].getPath());
            LOG.info("getLastGoodCheckpoint: Found last good checkpoint " +
                     m_lastCheckpointedSuperstep + " from " +
                     fileStatusArray[fileStatusArray.length - 1].
                     getPath().toString());
        }
        return m_lastCheckpointedSuperstep;
    }

    @Override
    public SuperstepState coordinateSuperstep() throws
            KeeperException, InterruptedException {
        // 1. Get chosen workers and set up watches on them.
        // 2. Assign partitions to the workers
        //    or possibly reload from a superstep
        // 3. Wait for all workers to complete
        // 4. Collect and process aggregators
        // 5. Create superstep finished node
        // 6. If the checkpoint frequency is met, finalize the checkpoint
        Map<String, JSONArray> chosenWorkerHostnamePortMap = checkWorkers();
        if (chosenWorkerHostnamePortMap == null) {
            LOG.fatal("coordinateSuperstep: Not enough healthy workers for " +
                      "superstep " + getSuperstep());
            setJobState(State.FAILED, -1, -1);
        } else {
            for (Entry<String, JSONArray> entry :
                    chosenWorkerHostnamePortMap.entrySet()) {
                String workerHealthyPath =
                    getWorkerHealthyPath(getApplicationAttempt(),
                                         getSuperstep()) + "/" + entry.getKey();
                if (getZkExt().exists(workerHealthyPath, true) == null) {
                    LOG.warn("coordinateSuperstep: Chosen worker " +
                             workerHealthyPath +
                             " is no longer valid, failing superstep");
                }
            }
        }

        if (getRestartedSuperstep() == getSuperstep()) {
            try {
                LOG.info("coordinateSuperstep: Reloading from superstep " +
                         getSuperstep());
                mapFilesToWorkers(
                    getRestartedSuperstep(),
                    new ArrayList<String>(
                        chosenWorkerHostnamePortMap.keySet()));
                inputSplitsToVertexRanges(chosenWorkerHostnamePortMap);
            } catch (IOException e) {
                throw new IllegalStateException(
                    "coordinateSuperstep: Failed to reload", e);
            }
        } else {
            if (getSuperstep() > 0) {
                BspBalancer<I, V, E, M> vertexRangeBalancer = null;
                try {
                    vertexRangeBalancer = (BspBalancer<I, V, E, M>)
                        m_vertexRangeBalancerClass.newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                synchronized (m_vertexRangeSynchronization) {
                    balanceVertexRanges(vertexRangeBalancer,
                                        chosenWorkerHostnamePortMap);
                }
            }
        }

        String finishedWorkerPath =
            getWorkerFinishedPath(getApplicationAttempt(), getSuperstep());
        try {
            getZkExt().createExt(finishedWorkerPath,
                                 null,
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT,
                                 true);
        } catch (KeeperException.NodeExistsException e) {
            LOG.info("coordinateSuperstep: finishedWorkers " +
                     finishedWorkerPath + " already exists, no need to create");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String workerHealthyPath =
            getWorkerHealthyPath(getApplicationAttempt(), getSuperstep());
        List<String> finishedWorkerList = null;
        while (true) {
            try {
                finishedWorkerList =
                    getZkExt().getChildrenExt(finishedWorkerPath,
                                              true,
                                              false,
                                              false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("coordinateSuperstep: Got finished worker list = " +
                          finishedWorkerList + ", size = " +
                          finishedWorkerList.size() +
                          ", chosen worker list = " +
                          chosenWorkerHostnamePortMap.keySet() + ", size = " +
                          chosenWorkerHostnamePortMap.size() +
                          " from " + finishedWorkerPath);
            }

            if (LOG.isInfoEnabled()) {
                LOG.info("coordinateSuperstep: " + finishedWorkerList.size() +
                         " out of " +
                         chosenWorkerHostnamePortMap.size() +
                         " chosen workers finished on superstep " +
                         getSuperstep());
            }
            getContext().setStatus(getBspMapper().getMapFunctions() + " " +
                                   finishedWorkerList.size() +
                                   " finished out of " +
                                   chosenWorkerHostnamePortMap.size());
            if (finishedWorkerList.containsAll(
                chosenWorkerHostnamePortMap.keySet())) {
                break;
            }
            getSuperstepStateChangedEvent().waitForever();
            getSuperstepStateChangedEvent().reset();

            // Did a worker die?
            if ((getSuperstep() > 0) &&
                    !superstepChosenWorkerAlive(
                        workerHealthyPath,
                        chosenWorkerHostnamePortMap.keySet())) {
                return SuperstepState.WORKER_FAILURE;
            }
        }
        collectAndProcessAggregatorValues(getSuperstep());
        Map<I, JSONObject> maxIndexStatsMap =
            collectVertexRangeStats(getSuperstep());
        long verticesFinished = 0;
        long verticesTotal = 0;
        long edgesTotal = 0;
        for (Map.Entry<I, JSONObject> entry : maxIndexStatsMap.entrySet()) {
            try {
                verticesFinished +=
                    entry.getValue().getLong(JSONOBJ_FINISHED_VERTICES_KEY);
                verticesTotal +=
                    entry.getValue().getLong(JSONOBJ_NUM_VERTICES_KEY);
                edgesTotal +=
                    entry.getValue().getLong(JSONOBJ_NUM_EDGES_KEY);
            } catch (JSONException e) {
                throw new RuntimeException(
                    "coordinateSuperstep: Failed to parse out "+
                    "the number range stats", e);
            }
        }
        if (LOG.isInfoEnabled()) {
            LOG.info("coordinateSuperstep: Aggregation found "
                     + verticesFinished + " of " + verticesTotal +
                     " vertices finished, " + edgesTotal +
                     " edges on superstep = " + getSuperstep());
        }

        // Convert the input split stats to vertex ranges in superstep 0
        if (getSuperstep() == 0) {
            inputSplitsToVertexRanges(chosenWorkerHostnamePortMap);
        }

        // Let everyone know the aggregated application state through the
        // superstep
        String superstepFinishedNode =
            getSuperstepFinishedPath(getApplicationAttempt(), getSuperstep());
        try {
            JSONObject globalInfoObject = new JSONObject();
            globalInfoObject.put(JSONOBJ_FINISHED_VERTICES_KEY, verticesFinished);
            globalInfoObject.put(JSONOBJ_NUM_VERTICES_KEY, verticesTotal);
            globalInfoObject.put(JSONOBJ_NUM_EDGES_KEY, edgesTotal);
            getZkExt().createExt(superstepFinishedNode,
                                 globalInfoObject.toString().getBytes(),
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT,
                                 true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Finalize the valid checkpoint file prefixes and possibly
        // the aggregators.
        if (checkpointFrequencyMet(getSuperstep())) {
            try {
                finalizeCheckpoint(
                    getSuperstep(),
                    new ArrayList<String>(chosenWorkerHostnamePortMap.keySet()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        // Clean up the old supersteps (always keep this one)
        long removeableSuperstep = getSuperstep() - 1;
        if ((getConfiguration().getBoolean(
                BspJob.KEEP_ZOOKEEPER_DATA,
                BspJob.KEEP_ZOOKEEPER_DATA_DEFAULT) == false) &&
            (removeableSuperstep >= 0)) {
            String oldSuperstepPath =
                getSuperstepPath(getApplicationAttempt()) + "/" +
                (removeableSuperstep);
            try {
                LOG.info("coordinateSuperstep: Cleaning up old Superstep " +
                         oldSuperstepPath);
                getZkExt().deleteExt(oldSuperstepPath,
                                     -1,
                                     true);
            } catch (KeeperException.NoNodeException e) {
                LOG.warn("coordinateBarrier: Already cleaned up " +
                         oldSuperstepPath);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        incrCachedSuperstep();
        m_superstepCounter.increment(1);
        return (verticesFinished == verticesTotal) ?
            SuperstepState.ALL_SUPERSTEPS_DONE :
            SuperstepState.THIS_SUPERSTEP_DONE;
    }

    private void cleanUpZooKeeper() {
        try {
            getZkExt().createExt(CLEANED_UP_PATH,
                                 null,
                                 Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT,
                                 true);
        } catch (KeeperException.NodeExistsException e) {
            LOG.info("cleanUpZooKeeper: Node " + CLEANED_UP_PATH +
                      " already exists, no need to create.");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Need to wait for the number of workers and masters to complete
        int maxTasks = BspInputFormat.getMaxTasks(getConfiguration());
        if (getBspMapper().getMapFunctions() == MapFunctions.ALL) {
            maxTasks *= 2;
        }
        List<String> cleanedUpChildrenList = null;
        while (true) {
            try {
                cleanedUpChildrenList =
                    getZkExt().getChildrenExt(CLEANED_UP_PATH, true, false, true);
                LOG.info("cleanUpZooKeeper: Got " +
                         cleanedUpChildrenList.size() + " of " +
                         maxTasks  +  " desired children from " +
                         CLEANED_UP_PATH);
                if (cleanedUpChildrenList.size() == maxTasks) {
                    break;
                }
                LOG.info("cleanedUpZooKeeper: Waiting for the children of " +
                         CLEANED_UP_PATH + " to change since only got " +
                         cleanedUpChildrenList.size() + " nodes.");
            }
            catch (Exception e) {
                // We are in the cleanup phase -- just log the error
                LOG.error(e.getMessage());
                return;
            }

            getCleanedUpChildrenChangedEvent().waitForever();
            getCleanedUpChildrenChangedEvent().reset();
        }

         // At this point, all processes have acknowledged the cleanup,
         // and the master can do any final cleanup
        try {
            if (getConfiguration().getBoolean(
                BspJob.KEEP_ZOOKEEPER_DATA,
                BspJob.KEEP_ZOOKEEPER_DATA_DEFAULT) == false) {
                LOG.info("cleanupZooKeeper: Removing the following path " +
                         "and all children - " + BASE_PATH);
                getZkExt().deleteExt(BASE_PATH, -1, true);
            }
        } catch (Exception e) {
            LOG.error("cleanupZooKeeper: Failed to do cleanup of " + BASE_PATH);
        }
    }

    @Override
    public void cleanup() throws IOException {
        // All master processes should denote they are done by adding special
        // znode.  Once the number of znodes equals the number of partitions
        // for workers and masters, the master will clean up the ZooKeeper
        // znodes associated with this job.
        String cleanedUpPath = CLEANED_UP_PATH  + "/" +
            getTaskPartition() + MASTER_SUFFIX;
        try {
            String finalFinishedPath =
                getZkExt().createExt(cleanedUpPath,
                                     null,
                                     Ids.OPEN_ACL_UNSAFE,
                                     CreateMode.PERSISTENT,
                                     true);
            LOG.info("cleanup: Notifying master its okay to cleanup with " +
                     finalFinishedPath);
        } catch (KeeperException.NodeExistsException e) {
            LOG.info("cleanup: Couldn't create finished node '" +
                     cleanedUpPath);
        } catch (Exception e) {
            // cleanup phase -- just log the error
            LOG.error(e.getMessage());
        }

        if (m_isMaster) {
            cleanUpZooKeeper();
            // If desired, cleanup the checkpoint directory
            if (getConfiguration().getBoolean(
                    BspJob.CLEANUP_CHECKPOINTS_AFTER_SUCCESS,
                    BspJob.CLEANUP_CHECKPOINTS_AFTER_SUCCESS_DEFAULT)) {
                boolean success =
                    getFs().delete(new Path(CHECKPOINT_BASE_PATH), true);
                LOG.info("cleanup: Removed HDFS checkpoint directory (" +
                         CHECKPOINT_BASE_PATH + ") with return = " + success +
                " since this job succeeded ");
            }
        }

        try {
            getZkExt().close();
        } catch (InterruptedException e) {
            // cleanup phase -- just log the error
            LOG.error("cleanup: Zookeeper failed to close with " + e);
        }
    }

    /**
     * Event that the master watches that denotes if a worker has done something
     * that changes the state of a superstep (either a worker completed or died)
     *
     * @return Event that denotes a superstep state change
     */
    final public BspEvent getSuperstepStateChangedEvent() {
        return m_superstepStateChanged;
    }

    /**
     * Should this worker failure cause the current superstep to fail?
     *
     * @param failedWorkerPath Full path to the failed worker
     */
    final private void checkHealthyWorkerFailure(String failedWorkerPath) {
        synchronized(m_vertexRangeSynchronization) {
            if (getSuperstepFromPath(failedWorkerPath) < getSuperstep()) {
                return;
            }

            // Check all the workers used in this superstep
            NavigableMap<I, VertexRange<I, V, E, M>> vertexRangeMap =
                getVertexRangeMap(getSuperstep());
            String hostnameId =
                getHealthyHostnameIdFromPath(failedWorkerPath);
            for (VertexRange<I, V, E, M> vertexRange :
                    vertexRangeMap.values()) {
                if (vertexRange.getHostnameId().equals(hostnameId) ||
                        ((vertexRange.getPreviousHostnameId() != null) &&
                                vertexRange.getPreviousHostnameId().equals(
                                    hostnameId))) {
                    LOG.warn("checkHealthyWorkerFailure: " +
                             "at least one healthy worker went down " +
                             "for superstep " + getSuperstep() + " - " +
                             hostnameId + ", will try to restart from " +
                             "checkpointed superstep " +
                             m_lastCheckpointedSuperstep);
                    m_superstepStateChanged.signal();
                }
            }
        }
    }

    @Override
    public boolean processEvent(WatchedEvent event) {
        boolean foundEvent = false;
        if (event.getPath().contains(WORKER_HEALTHY_DIR) &&
                (event.getType() == EventType.NodeDeleted)) {
            if (LOG.isInfoEnabled()) {
                LOG.info("processEvent: Healthy worker died (node deleted) " +
                         "in " + event.getPath());
            }
            checkHealthyWorkerFailure(event.getPath());
            m_superstepStateChanged.signal();
            foundEvent = true;
        } else if (event.getPath().contains(WORKER_FINISHED_DIR) &&
                event.getType() == EventType.NodeChildrenChanged) {
            if (LOG.isInfoEnabled()) {
                LOG.info("processEvent: Worker finished (node change) event - " +
                "m_superstepStateChanged signaled");
            }
            m_superstepStateChanged.signal();
            foundEvent = true;
        }

        return foundEvent;
    }
}
