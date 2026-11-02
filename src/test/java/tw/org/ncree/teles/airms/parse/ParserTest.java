package tw.org.ncree.teles.airms.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.Fixtures;
import tw.org.ncree.teles.airms.feed.SafeXml;
import tw.org.ncree.teles.airms.feed.XmlNode;

class ParserTest {

  private static XmlNode xml(String name) throws Exception {
    return SafeXml.parse(Fixtures.bytes(name), Fixtures.LIMITS);
  }

  @Test
  void feedTimeIsTaipei() {
    // 2026-10-28 16:05:00 台北 = 08:05:00 UTC
    assertEquals(Long.valueOf(1793174700000L), FeedTime.toEpochMs("2026-10-28 16:05:00"));
    assertNull(FeedTime.toEpochMs("2026-02-30 00:00:00"));
    assertNull(FeedTime.toEpochMs("bad"));
    assertNull(FeedTime.toEpochMs(null));
  }

  @Test
  void vdKeepsRawValuesAndCountsProblems() throws Exception {
    VdParser.Snapshot s = VdParser.parse(xml("vd_small.xml"));
    assertEquals("2026-10-28 16:05:00", s.feedTimeRaw());
    assertEquals("20", s.controlCenterId());
    assertEquals(3, s.devices().size());             // 重複的 VD 不收
    assertEquals(4, s.stats().total());
    VdParser.Device invalid = s.devices().get(1);
    assertEquals("1", invalid.commStatus());
    VdParser.Lane lane = invalid.laneList().get(0);
    assertEquals("0", lane.isDataValid());
    assertEquals("-2", lane.values().get("lane_total_car_volume")); // -2 照存，不轉成 0
    assertEquals("NA", lane.values().get("lane_use_rate"));
    assertEquals("x", s.devices().get(0).laneList().get(1).extra().get("new_attr")); // 未知屬性保留
    assertEquals(0, s.devices().get(2).laneList().size());   // 缺 laneId 的車道不收
    assertEquals(2, s.stats().bad());                          // 缺 laneId 1 次 + 重複 VD 1 次
    assertEquals(3, s.laneCount());
  }

  @Test
  void vdRejectsMissingTime() {
    byte[] bad = "<file_attribute><fivemin_vd_data/></file_attribute>".getBytes(StandardCharsets.UTF_8);
    assertThrows(FeedRejectedException.class, () -> VdParser.parse(SafeXml.parse(bad, Fixtures.LIMITS)));
  }

  @Test
  void sectionFiltersAndKeepsPartialRows() throws Exception {
    SectionParser.Snapshot s = SectionParser.parse(xml("section_small.xml"),
        SectionParser.Filter.from(Fixtures.sectionFilter()));
    assertEquals(2, s.rows().size());                    // 國5 與 300K 被過濾
    assertEquals(2, s.stats().skippedCount());
    assertEquals(1, s.stats().bad());                    // 國3 那筆總量是 NA
    SectionParser.Row n3 = s.rows().get(1);
    assertEquals("3", n3.freewayId());
    assertNull(n3.values().get("section_total_car_volume"));
    assertEquals(80, n3.values().get("section_average_speed"));
    assertEquals("99400", n3.fromMilepost());            // 里程保持字串原樣
  }

  @Test
  void rmsKeepsRowWithMissingFieldAndSkipsBlankIds() throws Exception {
    RmsParser.Snapshot s = RmsParser.parse(xml("rms_small.xml"), RmsParser.Filter.from(Fixtures.rmsFilter()));
    assertEquals(2, s.rows().size());
    assertEquals(2, s.stats().skippedCount());           // eqId 空白、T76 不在範圍
    assertEquals(1, s.stats().bad());
    assertEquals("閃黃", s.rows().get(0).values().get("rms_rate"));
    assertNull(s.rows().get(1).values().get("rms_rate"));
    assertEquals("", s.rows().get(0).values().get("eq_hw_status"));
  }
}
