package com.aliyun.log.logstash;

import com.aliyun.openservices.log.common.FastLog;
import com.aliyun.openservices.log.common.FastLogContent;
import com.aliyun.openservices.log.common.FastLogGroup;
import com.aliyun.openservices.log.common.FastLogTag;
import com.aliyun.openservices.log.common.LogGroupData;
import com.aliyun.openservices.loghub.client.ILogHubCheckPointTracker;
import com.aliyun.openservices.loghub.client.exceptions.LogHubCheckPointException;
import com.aliyun.openservices.loghub.client.interfaces.ILogHubProcessor;
import com.aliyun.openservices.loghub.client.interfaces.ILogHubProcessorFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import static javax.management.timer.Timer.ONE_SECOND;

public class LogstashLogHubProcessor implements ILogHubProcessor {
    private static final Logger logger = LogManager.getLogger(LogstashLogHubProcessor.class);
    /**
     * 缓存 从sls中拉取的数据
     */


    //shard id
    private int shardId;
    // 记录上次持久化 checkpoint 的时间
    private long mLastCheckTime = 0;
    //check point 到服务端 间隔时间
    private int checkpointSecond;
    //是否包含Meta
    private boolean includeMeta;
    //自processor启动后接收的所有日志数
    private long mTotalLogs = 0;
    //上次日志输出到现在接收的日志数
    //在每次更新checkpoint的时候输出，并置为0
    private long mNowLogs = 0;

    private String consumerName;

    private BlockingQueue<Map<String, String>> queueCache;
    @Override
    public void initialize(int shardId) {
        this.shardId = shardId;
    }
    
    public LogstashLogHubProcessor(int checkpointSecond, boolean includeMeta, String consumerName, BlockingQueue<Map<String, String>> queueCache) {
        this.checkpointSecond = checkpointSecond;
        this.includeMeta = includeMeta;
        this.consumerName = consumerName;
        this.queueCache = queueCache;
    }

    protected void showContent(Map<String, String> logMap) {
        try {
            this.queueCache.put(logMap);
        } catch (InterruptedException e) {
            logger.error("put result data to queue cache failed!", e);
        }
    }

    // 消费数据的主逻辑，这里面的所有异常都需要捕获，不能抛出去。
    @Override
    public String process(List<LogGroupData> logGroups,
                          ILogHubCheckPointTracker checkPointTracker) {
        logger.info(consumerName + " process_in_shardId:" + shardId);
        int count = 0;
        // 这里简单的将获取到的数据打印出来
        for (LogGroupData logGroup : logGroups) {
            FastLogGroup flg = logGroup.GetFastLogGroup();
            for (int lIdx = 0; lIdx < flg.getLogsCount(); ++lIdx) {
                FastLog log = flg.getLogs(lIdx);
                Map<String, String> logMap = new HashMap<>();
                if (includeMeta) {
                    logMap.put("__time__", String.valueOf(log.getTime()));
                    logMap.put("__source__", flg.getSource());
                    logMap.put("__topic__", flg.getTopic());
                    for (int tagIdx = 0; tagIdx < flg.getLogTagsCount(); ++tagIdx) {
                        FastLogTag logtag = flg.getLogTags(tagIdx);
                        logMap.put("__tag__:" + logtag.getKey(), logtag.getValue());
                    }
                }
                for (int cIdx = 0; cIdx < log.getContentsCount(); ++cIdx) {
                    FastLogContent content = log.getContents(cIdx);
                    logMap.put(content.getKey(), content.getValue());
                }
                showContent(logMap);
            }
            count = count + flg.getLogsCount();
            mTotalLogs = mTotalLogs + flg.getLogsCount();
            mNowLogs = mNowLogs + flg.getLogsCount();
        }
        if (count > 0) {
            logger.info(consumerName + " process_shardId:" + shardId + " input:" + count + " totalLogs:" + mTotalLogs + " nowLogs:" + mNowLogs);
        }
        long curTime = System.currentTimeMillis();
        // 每隔 30 秒，写一次 check point 到服务端，如果 10 秒内，worker crash，
        // 新启动的 worker 会从上一个 checkpoint 其消费数据，有可能有少量的重复数据
        if (curTime - mLastCheckTime > checkpointSecond * ONE_SECOND) {
            try {
                //参数true表示立即将checkpoint更新到服务端，为false会将checkpoint缓存在本地，后台默认隔60s会将checkpoint刷新到服务端。
                checkPointTracker.saveCheckPoint(true);
                mNowLogs = 0;
                logger.info(consumerName + " process_shardId:" + shardId +" saveCheckPoint " + checkPointTracker.getCheckPoint());
            } catch (LogHubCheckPointException e) {
                e.printStackTrace();
                logger.error(consumerName + " process_shardId:" + shardId + " saveCheckPoint ", e);
            }
            mLastCheckTime = curTime;
        }
        logger.info(consumerName + " process_out_shardId:" + shardId);
        return null;
    }

    // 当 worker 退出的时候，会调用该函数，用户可以在此处做些清理工作。
    @Override
    public void shutdown(ILogHubCheckPointTracker checkPointTracker) {
        //将消费断点保存到服务端。
        try {
            checkPointTracker.saveCheckPoint(true);
            logger.info(consumerName + " shutdown_shardId:" + shardId +" saveCheckPoint:" + checkPointTracker.getCheckPoint());
        } catch (LogHubCheckPointException e) {
            e.printStackTrace();
            logger.error(consumerName + " shutdown_shardId:" + shardId + " shutdown ", e);
        }
    }

    public void setCheckpointSecond(int checkpointSecond) {
        this.checkpointSecond = checkpointSecond;
    }


    public void setIncludeMeta(boolean includeMeta) {
        this.includeMeta = includeMeta;
    }

}

class LogstashLogHubProcessorFactory implements ILogHubProcessorFactory {

    private int checkpointSecond;
    private boolean includeMeta;
    private String consumerName;
    private BlockingQueue<Map<String, String>> queueCache;
    
    public LogstashLogHubProcessorFactory(int checkpointSecond, boolean includeMeta, String consumerName, BlockingQueue<Map<String, String>> queueCache) {
       this.checkpointSecond = checkpointSecond;
       this.includeMeta = includeMeta;
       this.consumerName = consumerName;
       this.queueCache = queueCache;
    }

    @Override
    public ILogHubProcessor generatorProcessor() {
        // 生成一个消费实例
        return new LogstashLogHubProcessor(this.checkpointSecond, this.includeMeta, this.consumerName, this.queueCache);
    }
}
