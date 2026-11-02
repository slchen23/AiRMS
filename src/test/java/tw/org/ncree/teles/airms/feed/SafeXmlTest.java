package tw.org.ncree.teles.airms.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.Fixtures;
import tw.org.ncree.teles.airms.config.AppConfig;

class SafeXmlTest {

  @Test
  void parsesAttributesOnlyTree() throws Exception {
    XmlNode root = SafeXml.parse(Fixtures.bytes("vd_small.xml"), Fixtures.LIMITS);
    assertEquals("file_attribute", root.name());
    assertEquals("2026-10-28 16:05:00", root.attr("time"));
    assertEquals(4, root.findAll("traffic_data").size());
    assertEquals(4, root.findAll("lane").size());
  }

  @Test
  void rejectsExternalEntity() {
    String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
        + "<r time=\"&x;\"/>";
    assertThrows(SafeXml.XmlRejectedException.class,
        () -> SafeXml.parse(xxe.getBytes(StandardCharsets.UTF_8), Fixtures.LIMITS));
  }

  @Test
  void rejectsInternalEntityExpansion() {
    String bomb = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]>"
        + "<r v=\"&b;\"/>";
    assertThrows(SafeXml.XmlRejectedException.class,
        () -> SafeXml.parse(bomb.getBytes(StandardCharsets.UTF_8), Fixtures.LIMITS));
  }

  @Test
  void rejectsTruncatedFile() {
    byte[] full = Fixtures.bytes("vd_small.xml");
    byte[] cut = new byte[full.length / 2];
    System.arraycopy(full, 0, cut, 0, cut.length);
    assertThrows(SafeXml.XmlRejectedException.class, () -> SafeXml.parse(cut, Fixtures.LIMITS));
  }

  @Test
  void enforcesElementAndDepthLimits() {
    byte[] xml = Fixtures.bytes("vd_small.xml");
    SafeXml.XmlRejectedException tooMany = assertThrows(SafeXml.XmlRejectedException.class,
        () -> SafeXml.parse(xml, new AppConfig.XmlLimits(5, 32, 1024)));
    assertTrue(tooMany.getMessage().contains("元素數"));
    SafeXml.XmlRejectedException tooDeep = assertThrows(SafeXml.XmlRejectedException.class,
        () -> SafeXml.parse(xml, new AppConfig.XmlLimits(50000, 3, 1024)));
    assertTrue(tooDeep.getMessage().contains("深度"));
  }

  @Test
  void rejectsEmptyInput() {
    assertThrows(SafeXml.XmlRejectedException.class, () -> SafeXml.parse(new byte[0], Fixtures.LIMITS));
  }
}
