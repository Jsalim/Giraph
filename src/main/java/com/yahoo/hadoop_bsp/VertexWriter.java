package com.yahoo.hadoop_bsp;

import java.io.IOException;

import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskInputOutputContext;

public interface VertexWriter<I, V, E> {
	
	/** 
	 * Writes the argument vertex and associated data
	 *
	 * @param context output context
	 * @param vertexId vertex id that is written out
	 * @param vertexValue vertex value that is written out
	 * @param destEdgeIt iterator over vertex edges written out
	 */      
	<KEYOUT,VALUEOUT> void write(TaskInputOutputContext<Object,Object,KEYOUT,VALUEOUT> context,
            I vertexId, V vertexValue, OutEdgeIterator<I, E> destEdgeIt) 
		throws IOException, InterruptedException;
	  
	/** 
	 * Close this {@link VertexWriter} to future operations.
	 * 
     * @param context the context of the task
	 * @throws IOException
	 */ 
	void close(TaskAttemptContext context) 
	    throws IOException, InterruptedException;
}
