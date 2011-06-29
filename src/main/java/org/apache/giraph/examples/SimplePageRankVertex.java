package org.apache.giraph.examples;

import java.util.Iterator;

import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.LongWritable;

import org.apache.log4j.Logger;

import org.apache.giraph.HadoopVertex;
import org.apache.giraph.lib.MaxAggregator;
import org.apache.giraph.lib.MinAggregator;
import org.apache.giraph.lib.LongSumAggregator;

/**
 * Demonstrates the basic Pregel PageRank implementation.
 */
public class SimplePageRankVertex extends
    HadoopVertex<LongWritable, DoubleWritable, FloatWritable, DoubleWritable> {
    /** User can access this sum after the application finishes if local */
    public static long finalSum;
    /** User can access this min after the application finishes if local */
    public static double finalMin;
    /** User can access this max after the application finishes if local */
    public static double finalMax;
    /** Logger */
    private static final Logger LOG =
        Logger.getLogger(SimplePageRankVertex.class);

    @Override
    public void preApplication()
            throws InstantiationException, IllegalAccessException {
        registerAggregator("sum", LongSumAggregator.class);
        registerAggregator("min", MinAggregator.class);
        registerAggregator("max", MaxAggregator.class);
    }

    @Override
    public void postApplication() {
        LongSumAggregator sumAggreg = (LongSumAggregator) getAggregator("sum");
        MinAggregator minAggreg = (MinAggregator) getAggregator("min");
        MaxAggregator maxAggreg = (MaxAggregator) getAggregator("max");
        finalSum = sumAggreg.getAggregatedValue().get();
        finalMin = minAggreg.getAggregatedValue().get();
        finalMax = maxAggreg.getAggregatedValue().get();

    }

    @Override
    public void preSuperstep() {
        LongSumAggregator sumAggreg = (LongSumAggregator) getAggregator("sum");
        MinAggregator minAggreg = (MinAggregator) getAggregator("min");
        MaxAggregator maxAggreg = (MaxAggregator) getAggregator("max");
        if (getSuperstep() >= 2) {
            LOG.info("aggregatedNumVertices=" +
                    sumAggreg.getAggregatedValue() +
                    " NumVertices=" + getNumVertices());
            if (sumAggreg.getAggregatedValue().get() != getNumVertices()) {
                throw new RuntimeException("wrong value of SumAggreg: " +
                        sumAggreg.getAggregatedValue() + ", should be: " +
                        getNumVertices());
            }
            DoubleWritable maxPagerank =
                    (DoubleWritable)maxAggreg.getAggregatedValue();
            LOG.info("aggregatedMaxPageRank=" + maxPagerank.get());
            DoubleWritable minPagerank =
                    (DoubleWritable)minAggreg.getAggregatedValue();
            LOG.info("aggregatedMinPageRank=" + minPagerank.get());
        }
        useAggregator("sum");
        useAggregator("min");
        useAggregator("max");
        sumAggreg.setAggregatedValue(new LongWritable(0L));
    }

    @Override
    public void compute(Iterator<DoubleWritable> msgIterator) {
        LongSumAggregator sumAggreg = (LongSumAggregator) getAggregator("sum");
        MinAggregator minAggreg = (MinAggregator) getAggregator("min");
        MaxAggregator maxAggreg = (MaxAggregator) getAggregator("max");
        double sum = 0;
        while (msgIterator.hasNext()) {
            sum += msgIterator.next().get();
        }
        DoubleWritable vertexValue =
            new DoubleWritable((0.15f / getNumVertices()) + 0.85f * sum);
        setVertexValue(vertexValue);
        maxAggreg.aggregate(vertexValue);
        minAggreg.aggregate(vertexValue);
        sumAggreg.aggregate(1L);
        LOG.info(getVertexId() + ": PageRank=" + vertexValue +
                 " max=" + maxAggreg.getAggregatedValue() +
                 " min=" + minAggreg.getAggregatedValue());
        if (getSuperstep() < 30) {
            long edges = getOutEdgeMap().size();
            sentMsgToAllEdges(
                new DoubleWritable(getVertexValue().get() / edges));
        } else {
            voteToHalt();
        }
    }

    @Override
    public DoubleWritable createMsgValue() {
        return new DoubleWritable(0f);
    }
}
