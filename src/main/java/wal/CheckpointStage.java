package wal;

/**
 * Durable phases of a checkpoint:
 * temp file write -> temp fsync -> atomic replace (+dir fsync)
 * -> checkpoint log frame -> rotated new segment.
 */
public enum CheckpointStage {
    TEMP_WRITTEN("1: 临时文件已写入（未同步）"),
    TEMP_SYNCED("2: 临时文件已 fsync（未替换）"),
    REPLACED("3: 原子替换完成（目录已同步）"),
    CKPT_LOGGED("4: checkpoint 日志帧已落盘"),
    ROTATED("5: 新日志段已创建");

    public final String label;

    CheckpointStage(String label) {
        this.label = label;
    }
}
