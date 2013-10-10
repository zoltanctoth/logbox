package com.prezi.logbox.mapreduce;

import com.hadoop.compression.lzo.LzopCodec;
import com.prezi.hadoop.OverwriteOutputDirTextOutputFormat;
import com.prezi.logbox.executor.HadoopExecutor;
import com.prezi.hadoop.IndexFilter;
import com.prezi.logbox.config.CategoryConfiguration;
import com.prezi.logbox.config.ExecutionContext;
import com.prezi.logbox.config.LogBoxConfiguration;
import com.prezi.logbox.config.Rule;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.Task;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.LazyOutputFormat;
import org.apache.hadoop.util.Tool;

import java.io.IOException;
import java.util.ArrayList;


public class LogBox extends Configured implements Tool {
    private static Log log = LogFactory.getLog(LogBox.class);
    private ExecutionContext executionContext;


    public LogBox(ExecutionContext c) {
        this.executionContext = c;
    }

    private ArrayList<String> ruleNames() {
        ArrayList<String> ruleNames = new ArrayList<String>();
        LogBoxConfiguration logBoxConfiguration = this.executionContext.getConfig();
        for (CategoryConfiguration c : logBoxConfiguration.getCategoryConfigurations()) {
            for (Rule r : c.getRules()) {
                ruleNames.add(r.getName());
            }
        }
        return ruleNames;
    }

    private Job createJob(Configuration conf) throws IOException {
        Job job = new Job(conf, "logbox");
        job.setJarByClass(HadoopExecutor.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(NullWritable.class);

        job.setMapperClass(SubstituteLineMapper.class);
        job.setReducerClass(SubstituteLineReducer.class);

        job.setJarByClass(HadoopExecutor.class);

        job.setInputFormatClass(TextInputFormat.class);
        job.setOutputFormatClass(OverwriteOutputDirTextOutputFormat.class);

        // TODO ez jo itt?
        job.setNumReduceTasks(39);

        FileOutputFormat.setCompressOutput(job, false);

        String inputLocationPrefix = executionContext.getConfig().getInputLocationPrefix();

        for (CategoryConfiguration c : executionContext.getConfig().getCategoryConfigurations()) {
            String inputLocation = inputLocationPrefix + c.getInputGlob();
            log.info("Adding input glob: " + inputLocation);
            FileInputFormat.setInputPathFilter(job, IndexFilter.class);
            FileInputFormat.addInputPath(job, new Path(inputLocation));
        }

        if ( executionContext.getConfig().getOutputCompression().equals("lzo")){
            FileOutputFormat.setCompressOutput(job, true);

            FileOutputFormat.setOutputCompressorClass(job, LzopCodec.class);
        }

        FileOutputFormat.setOutputPath(job, new Path(executionContext.getConfig().getOutputLocationBase()));
        LazyOutputFormat.setOutputFormatClass(job, OverwriteOutputDirTextOutputFormat.class);

        return job;
    }

    void printCounters(Job job) throws IOException {
        Counters counters = job.getCounters();
        long duplicates = counters.findCounter(LineCounter.EMITTED_IN_MAPPER).getValue()
                - counters.findCounter(LineCounter.SUBSTITUTED).getValue();
        System.out.println("Number of lines read by mappers: " + counters.findCounter(Task.Counter.MAP_INPUT_RECORDS).getValue() );
        System.out.println("Number of unchanged lines: " + counters.findCounter(LineCounter.OMITTED_IN_MAPPER).getValue());
        System.out.println("Number of lines written by reducers: " + counters.findCounter(LineCounter.SUBSTITUTED).getValue());
        System.out.println("Number of duplicates (filtered out by reducer) : " + duplicates);
        System.out.println("Number of malformed records from mapper: " + counters.findCounter(MalformedRecord.FROM_MAPPER).getValue());
        System.out.println("Number of input files: " + counters.findCounter(FileCounter.INPUT_FILES).getValue());

        ArrayList<String> ruleNames = ruleNames();
        for (String ruleName : ruleNames) {
            System.out.println("Number of records from rule type " + ruleName + ": " + counters.findCounter("RuleTypesInMapper", ruleName).getValue());
        }
    }

    @Override
    public int run(String[] strings) throws Exception {

        Configuration conf = new Configuration();
        conf.setStrings("config.json", executionContext.getConfig().toJSON());
        conf.set("date_glob", executionContext.getDateGlob());

        Job job = createJob(conf);

        try {
            job.waitForCompletion(true);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        try {
            printCounters(job);
        } catch (IOException e){
            e.printStackTrace();
        }
        return 0;
    }

}