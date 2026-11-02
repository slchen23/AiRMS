package tw.org.ncree.teles.airms.feed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 只含元素名稱與屬性的精簡 XML 節點；三個來源都只用屬性，不用文字內容。 */
public final class XmlNode {
  private final String name;
  private final Map<String, String> attributes;
  private final List<XmlNode> children = new ArrayList<>();

  XmlNode(String name, Map<String, String> attributes) {
    this.name = name;
    this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
  }

  void add(XmlNode child) {
    children.add(child);
  }

  public String name() {
    return name;
  }

  /** 屬性不存在回 null；存在但為空字串回 ""（兩者意義不同，呼叫端自行判斷）。 */
  public String attr(String key) {
    return attributes.get(key);
  }

  public Map<String, String> attributes() {
    return attributes;
  }

  public List<XmlNode> children() {
    return Collections.unmodifiableList(children);
  }

  /** 深度優先找出所有指定名稱的元素（含自己）。 */
  public List<XmlNode> findAll(String elementName) {
    List<XmlNode> out = new ArrayList<>();
    visit(n -> {
      if (n.name.equals(elementName)) out.add(n);
    });
    return out;
  }

  private void visit(Consumer<XmlNode> action) {
    ArrayList<XmlNode> stack = new ArrayList<>();
    stack.add(this);
    while (!stack.isEmpty()) {
      XmlNode n = stack.remove(stack.size() - 1);
      action.accept(n);
      for (int i = n.children.size() - 1; i >= 0; i--) stack.add(n.children.get(i));
    }
  }
}
