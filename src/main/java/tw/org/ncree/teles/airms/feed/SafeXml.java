package tw.org.ncree.teles.airms.feed;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import tw.org.ncree.teles.airms.config.AppConfig;

/**
 * 安全的 XML 解析：不處理 DTD、不載入外部實體，並限制元素數、深度、屬性長度。
 * 超過限制或格式錯誤一律丟 XmlRejectedException，不會產生「看起來完整」的半份資料。
 */
public final class SafeXml {
  private SafeXml() { }

  public static final class XmlRejectedException extends Exception {
    private static final long serialVersionUID = 1L;

    public XmlRejectedException(String message) {
      super(message);
    }

    public XmlRejectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static XmlNode parse(byte[] bytes, AppConfig.XmlLimits limits) throws XmlRejectedException {
    if (bytes == null || bytes.length == 0) throw new XmlRejectedException("XML 內容是空的");
    XMLInputFactory f = XMLInputFactory.newDefaultFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    f.setProperty(XMLInputFactory.IS_COALESCING, false);
    trySet(f, XMLConstants.ACCESS_EXTERNAL_DTD, "");
    trySet(f, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

    XMLStreamReader r = null;
    try {
      r = f.createXMLStreamReader(new ByteArrayInputStream(bytes));
      Deque<XmlNode> stack = new ArrayDeque<>();
      XmlNode root = null;
      int elements = 0;
      while (r.hasNext()) {
        int event = r.next();
        switch (event) {
          case XMLStreamConstants.DTD -> throw new XmlRejectedException("不接受含 DTD 的 XML");
          case XMLStreamConstants.ENTITY_REFERENCE -> throw new XmlRejectedException("不接受實體參照");
          case XMLStreamConstants.START_ELEMENT -> {
            if (++elements > limits.maxElements()) {
              throw new XmlRejectedException("元素數超過上限 " + limits.maxElements());
            }
            if (stack.size() + 1 > limits.maxDepth()) {
              throw new XmlRejectedException("巢狀深度超過上限 " + limits.maxDepth());
            }
            Map<String, String> attrs = new LinkedHashMap<>();
            for (int i = 0; i < r.getAttributeCount(); i++) {
              String key = r.getAttributeLocalName(i);
              String value = r.getAttributeValue(i);
              if (value != null && value.length() > limits.maxAttributeLength()) {
                throw new XmlRejectedException("屬性 " + key + " 長度超過上限 " + limits.maxAttributeLength());
              }
              if (attrs.put(key, value) != null) throw new XmlRejectedException("重複的屬性 " + key);
            }
            XmlNode node = new XmlNode(r.getLocalName(), attrs);
            if (stack.isEmpty()) {
              if (root != null) throw new XmlRejectedException("XML 有多個根元素");
              root = node;
            } else {
              stack.peek().add(node);
            }
            stack.push(node);
          }
          case XMLStreamConstants.END_ELEMENT -> stack.pop();
          default -> { }
        }
      }
      if (root == null) throw new XmlRejectedException("XML 沒有根元素");
      if (!stack.isEmpty()) throw new XmlRejectedException("XML 未正常結束（可能被截斷）");
      return root;
    } catch (XMLStreamException e) {
      throw new XmlRejectedException("XML 格式錯誤：" + oneLine(e.getMessage()), e);
    } finally {
      if (r != null) {
        try {
          r.close();
        } catch (XMLStreamException ignored) {
          // 讀取器已無資源可釋放
        }
      }
    }
  }

  private static void trySet(XMLInputFactory f, String key, Object value) {
    try {
      f.setProperty(key, value);
    } catch (IllegalArgumentException ignored) {
      // 部分實作不支援這個屬性；DTD 與外部實體已在上面關閉
    }
  }

  static String oneLine(String s) {
    if (s == null) return "";
    String t = s.replace('\r', ' ').replace('\n', ' ');
    return t.length() > 300 ? t.substring(0, 300) + "…" : t;
  }
}
