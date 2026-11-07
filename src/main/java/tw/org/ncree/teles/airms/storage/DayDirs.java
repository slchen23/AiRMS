package tw.org.ncree.teles.airms.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 原始 XML 的 &lt;來源&gt;/yyyy/MM/dd 日期資料夾（備份與資料保留共用）。 */
public final class DayDirs {
  private DayDirs() { }

  private static final Pattern YEAR = Pattern.compile("\\d{4}");
  private static final Pattern TWO = Pattern.compile("\\d{2}");

  public record Day(LocalDate date, Path dir) { }

  public static List<Day> of(Path sourceDir) throws IOException {
    List<Day> out = new ArrayList<>();
    for (Path y : list(sourceDir)) {
      if (!Files.isDirectory(y) || !YEAR.matcher(y.getFileName().toString()).matches()) continue;
      for (Path m : list(y)) {
        if (!Files.isDirectory(m) || !TWO.matcher(m.getFileName().toString()).matches()) continue;
        for (Path d : list(m)) {
          if (!Files.isDirectory(d) || !TWO.matcher(d.getFileName().toString()).matches()) continue;
          try {
            out.add(new Day(LocalDate.of(Integer.parseInt(y.getFileName().toString()),
                Integer.parseInt(m.getFileName().toString()), Integer.parseInt(d.getFileName().toString())), d));
          } catch (RuntimeException ignored) {
            // 不是合法日期的資料夾不處理
          }
        }
      }
    }
    out.sort((a, b) -> a.date().compareTo(b.date()));
    return out;
  }

  private static List<Path> list(Path dir) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.sorted().toList();
    }
  }
}
