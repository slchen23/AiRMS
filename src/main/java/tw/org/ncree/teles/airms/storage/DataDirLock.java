package tw.org.ncree.teles.airms.storage;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 同一個資料目錄同時只允許一個 AiRMS 使用（例如 IntelliJ 開發中的程式與長時間累積資料的程式）。
 * 用作業系統的檔案鎖：程式被強制結束時鎖會自動釋放，不會留下「假鎖」擋住下次啟動。
 */
public final class DataDirLock implements AutoCloseable {
  public static final String FILE_NAME = ".airms-lock";

  private final FileChannel channel;
  private final FileLock lock;

  private DataDirLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  public static DataDirLock acquire(DataVolume volume) {
    Path file = volume.root().resolve(FILE_NAME);
    FileChannel ch = null;
    try {
      ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock l;
      try {
        l = ch.tryLock(0, Long.MAX_VALUE, false);
      } catch (OverlappingFileLockException e) {
        l = null;
      }
      if (l == null) {
        ch.close();
        throw new IllegalStateException("資料目錄 " + volume.root() + " 已有另一個 AiRMS 在使用，"
            + "請先停止那一個，或讓這次執行改用別的資料目錄");
      }
      String info = "pid=" + ProcessHandle.current().pid() + " host=" + host() + " since=" + Instant.now() + "\n";
      ch.truncate(0);
      ch.write(ByteBuffer.wrap(info.getBytes(StandardCharsets.UTF_8)), 0);
      return new DataDirLock(ch, l);
    } catch (IOException e) {
      if (ch != null) {
        try {
          ch.close();
        } catch (IOException ignored) {
          // 已在處理錯誤
        }
      }
      throw new IllegalStateException("無法鎖定資料目錄 " + volume.root() + "：" + e.getMessage());
    }
  }

  static String host() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException e) {
      return "unknown";
    }
  }

  @Override
  public void close() {
    try {
      lock.release();
    } catch (IOException ignored) {
      // 關閉通道時作業系統也會釋放
    }
    try {
      channel.close();
    } catch (IOException ignored) {
      // 程式結束時作業系統會回收
    }
  }
}
