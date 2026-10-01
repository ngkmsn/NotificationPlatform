package com.notification.api.dto;

import java.util.List;
import java.util.Map;

public class DashboardQueuesDto {

    public static class PriorityQueueStat {
        private String priority;
        private long total;
        private long pending;
        private long delivered;
        private long failed;
        private long dlq;

        public PriorityQueueStat() {}

        public PriorityQueueStat(String priority, long total, long pending, long delivered, long failed, long dlq) {
            this.priority = priority;
            this.total = total;
            this.pending = pending;
            this.delivered = delivered;
            this.failed = failed;
            this.dlq = dlq;
        }

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }

        public long getPending() { return pending; }
        public void setPending(long pending) { this.pending = pending; }

        public long getDelivered() { return delivered; }
        public void setDelivered(long delivered) { this.delivered = delivered; }

        public long getFailed() { return failed; }
        public void setFailed(long failed) { this.failed = failed; }

        public long getDlq() { return dlq; }
        public void setDlq(long dlq) { this.dlq = dlq; }
    }

    public static class ChannelQueueStat {
        private String channel;
        private long total;
        private long delivered;
        private long failed;
        private long dlq;

        public ChannelQueueStat() {}

        public ChannelQueueStat(String channel, long total, long delivered, long failed, long dlq) {
            this.channel = channel;
            this.total = total;
            this.delivered = delivered;
            this.failed = failed;
            this.dlq = dlq;
        }

        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }

        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }

        public long getDelivered() { return delivered; }
        public void setDelivered(long delivered) { this.delivered = delivered; }

        public long getFailed() { return failed; }
        public void setFailed(long failed) { this.failed = failed; }

        public long getDlq() { return dlq; }
        public void setDlq(long dlq) { this.dlq = dlq; }
    }

    private List<PriorityQueueStat> byPriority;
    private List<ChannelQueueStat> byChannel;

    public DashboardQueuesDto() {}

    public DashboardQueuesDto(List<PriorityQueueStat> byPriority, List<ChannelQueueStat> byChannel) {
        this.byPriority = byPriority;
        this.byChannel = byChannel;
    }

    public List<PriorityQueueStat> getByPriority() { return byPriority; }
    public void setByPriority(List<PriorityQueueStat> byPriority) { this.byPriority = byPriority; }

    public List<ChannelQueueStat> getByChannel() { return byChannel; }
    public void setByChannel(List<ChannelQueueStat> byChannel) { this.byChannel = byChannel; }
}
