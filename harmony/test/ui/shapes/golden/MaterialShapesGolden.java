// Generates material_shapes_golden.json from the real Kotlin classes Android
// Yoin ships (material3 1.5.0-alpha27 MaterialShapes, graphics-shapes 1.1.0).
// Classpath: material3, graphics-shapes, compose ui-graphics / ui-geometry /
// ui-unit / ui-util / runtime (classes.jar of each AAR), androidx.collection
// (jvm) and kotlin-stdlib, all from ~/.gradle/caches. Then:
//   javac -cp "$CP" -d out MaterialShapesGolden.java
//   java -cp "$CP:out" MaterialShapesGolden > material_shapes_golden.json
import androidx.compose.material3.MaterialShapes;
import androidx.graphics.shapes.*;
import java.util.*;

public class MaterialShapesGolden {
  static String pts(List<Cubic> cs) {
    StringBuilder sb = new StringBuilder("[");
    for (Cubic c : cs) {
      float[] p = new float[]{c.getAnchor0X(), c.getAnchor0Y(), c.getControl0X(), c.getControl0Y(), c.getControl1X(), c.getControl1Y(), c.getAnchor1X(), c.getAnchor1Y()};
      for (float f : p) sb.append(f).append(",");
    }
    if (sb.length() > 1) sb.setLength(sb.length() - 1);
    return sb.append("]").toString();
  }
  static String b(float[] f) { return "[" + f[0] + "," + f[1] + "," + f[2] + "," + f[3] + "]"; }
  public static void main(String[] a) throws Exception {
    MaterialShapes.Companion m = MaterialShapes.Companion;
    String[] names = {"Circle","Square","Slanted","Arch","Fan","Arrow","SemiCircle","Oval","Pill","Triangle","Diamond","ClamShell","Pentagon","Gem","Sunny","VerySunny","Cookie4Sided","Cookie6Sided","Cookie7Sided","Cookie9Sided","Cookie12Sided","Ghostish","Clover4Leaf","Clover8Leaf","Burst","SoftBurst","Boom","SoftBoom","Flower","Puffy","PuffyDiamond","PixelCircle","PixelTriangle","Bun","Heart"};
    StringBuilder out = new StringBuilder("{\"shapes\":{");
    Map<String, RoundedPolygon> polys = new HashMap<>();
    for (String n : names) {
      RoundedPolygon p = (RoundedPolygon) m.getClass().getMethod("get" + n).invoke(m);
      polys.put(n, p);
      out.append("\"").append(n).append("\":{\"features\":").append(p.getFeatures().size())
         .append(",\"cubics\":").append(p.getCubics().size())
         .append(",\"bounds\":").append(b(p.calculateBounds(new float[4], true)))
         .append(",\"maxBounds\":").append(b(p.calculateMaxBounds(new float[4])))
         .append(",\"center\":[").append(p.getCenterX()).append(",").append(p.getCenterY()).append("]")
         .append(",\"points\":").append(pts(p.getCubics())).append("},");
    }
    out.setLength(out.length() - 1);
    out.append("},\"morphs\":{");
    // Every morph Yoin draws (Home press, landing, restore sheet, tips, the
    // avatar slots), less the pairs whose corner matching is a Float tie (see
    // material_shapes_test.dart), plus the two that once lost a cubic at the
    // cut (Clover4Leaf>Gem, Ghostish>Arch).
    String[][] pairs = {{"Bun","Triangle"},{"Circle","Triangle"},{"Ghostish","Triangle"},{"Circle","Cookie9Sided"},{"Cookie6Sided","Clover4Leaf"},{"Pentagon","Cookie9Sided"},{"Oval","Cookie6Sided"},
        {"Circle","SoftBurst"},{"Sunny","Cookie6Sided"},{"Cookie6Sided","Gem"},{"Gem","Pentagon"},
        {"Clover4Leaf","Gem"},{"Ghostish","Arch"},{"Gem","Arch"},{"Arch","Slanted"},{"Slanted","Ghostish"},{"Ghostish","Sunny"},{"Sunny","Oval"},{"Oval","Cookie6Sided"},{"Arch","Gem"}};
    Set<String> seen = new HashSet<>();
    for (String[] pr : pairs) {
      if (!seen.add(pr[0] + ">" + pr[1])) continue;
      Morph mo = new Morph(polys.get(pr[0]), polys.get(pr[1]));
      out.append("\"").append(pr[0]).append(">").append(pr[1]).append("\":{\"pairs\":").append(mo.getMorphMatch().size())
         .append(",\"half\":").append(pts(mo.asCubics(0.5f)))
         .append(",\"overshoot\":").append(pts(mo.asCubics(1.08f))).append("},");
    }
    out.setLength(out.length() - 1);
    // LoadingIndicator's morphSequence normalizes each shape again.
    String[] li = {"SoftBurst","Cookie9Sided","Pentagon","Pill","Sunny","Cookie4Sided","Oval"};
    out.append("},\"loadingMorphs\":{");
    for (int i = 0; i < li.length; i++) {
      String x = li[i], y = li[(i + 1) % li.length];
      Morph mo = new Morph(polys.get(x).normalized(), polys.get(y).normalized());
      out.append("\"").append(x).append(">").append(y).append("\":{\"pairs\":").append(mo.getMorphMatch().size())
         .append(",\"half\":").append(pts(mo.asCubics(0.5f))).append("},");
    }
    out.setLength(out.length() - 1);
    out.append("}}");
    System.out.println(out);
  }
}
