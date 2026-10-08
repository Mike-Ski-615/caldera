package com.caldera.shaders.screen;

import com.caldera.shaders.graph.PackGraph;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选项面板的**纯逻辑**：一个值读成什么标签、算不算脏、重置回什么、哪些键算"基础"。
 * <p>
 * 这个类此前 0 测试。它一直是纯的（只吃一个 {@link PackGraph}），之所以没人测，是因为它
 * 只被 {@code NativePackSettingsScreen} 用，而那个屏幕要开窗口。这次顺手把它接上——
 * 候选 3 的那份收益清单里写着"解锁 0 测试的纯逻辑"，指的就是这里。
 * <p>
 * 夹具用内置包真实的清单（与 {@code PackGraphTest} 同一份），所以断言里的默认值与取值档位
 * 都是包自己声明的，不是测试编出来的。
 */
class PackSettingsModelTest {

   private static final String REAL_MANIFEST = readFixture();

   private static String readFixture() {
      try (InputStream stream = PackSettingsModelTest.class.getResourceAsStream("/caldera-realistic.json")) {
         assertNotNull(stream, "测试夹具 /caldera-realistic.json 必须存在");
         return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException failure) {
         throw new AssertionError(failure);
      }
   }

   private static PackSettingsModel model() {
      return new PackSettingsModel(PackGraph.parse(REAL_MANIFEST));
   }

   /** 一份只有个别选项的最小清单，用来测"这个包没有任何基础选项"那条回退。 */
   private static PackSettingsModel minimalModel(String optionsJson) {
      return new PackSettingsModel(PackGraph.parse("""
            {
              "version": 1,
              "name": "test",
              "resources": {"display": {"format": "RGBA8_UNORM"}},
              "passes": [{"name": "only", "fragment": "shaders/only.fsh", "reads": {}, "writes": ["display"]}],
              "present": "display",
              "options": %s
            }
            """.formatted(optionsJson)));
   }

   // ---------------------------------------------------------------- 标签

   /**
    * 四档 Color Style。这一条是本候选改动的直接验证：原先写成嵌套三元、末尾落到 {@code "Vibrant"}，
    * 看起来像在兜 0.75 那个旧档位；现在按定义里的位置取标签，四档一一对应。
    * <p>
    * 用 {@code change()} 按定义顺序走一圈，所以顺序错了也会被抓到。
    */
   @Test
   void theFourColorGradesReadOutAsTheirLabelsInDefinitionOrder() {
      PackSettingsModel model = model();

      assertEquals("Realistic", model.value("COLOR_GRADE", false), "内置包默认 0.5");
      model.change("COLOR_GRADE", false);
      assertEquals("Vibrant", model.value("COLOR_GRADE", false));
      model.change("COLOR_GRADE", false);
      assertEquals("Off", model.value("COLOR_GRADE", false));
      model.change("COLOR_GRADE", false);
      assertEquals("Natural", model.value("COLOR_GRADE", false));
      model.change("COLOR_GRADE", false);
      assertEquals("Realistic", model.value("COLOR_GRADE", false), "绕回起点");
   }

   @Test
   void aBinaryOptionReadsAsOnOrOff() {
      PackSettingsModel model = model();

      assertEquals("On", model.value("WATER_ENABLED", false), "内置包默认 1");
      model.change("WATER_ENABLED", false);
      assertEquals("Off", model.value("WATER_ENABLED", false));
      model.change("WATER_ENABLED", false);
      assertEquals("On", model.value("WATER_ENABLED", false), "再切回来是它原来的档位，不是第二档");
   }

   @Test
   void aQualityOptionReadsAsTheFiveStepLadder() {
      PackSettingsModel model = model();

      assertEquals("Medium", model.value("SHADOW_QUALITY", false), "内置包默认 2");
      model.change("SHADOW_QUALITY", false);
      assertEquals("High", model.value("SHADOW_QUALITY", false));
      model.change("SHADOW_QUALITY", false);
      assertEquals("Ultra", model.value("SHADOW_QUALITY", false));
      model.change("SHADOW_QUALITY", false);
      assertEquals("Off", model.value("SHADOW_QUALITY", false), "走完四档绕回 0");
   }

   /**
    * 五档、首档为 0、且在"基础"列表里的选项也走梯形，但**只在基础视图里**：
    * 高级视图下它显示原始数值。这是原件的行为，不是笔误。
    */
   @Test
   void aBasicFiveChoiceOptionUsesTheLadderOnlyInTheBasicView() {
      PackSettingsModel model = model();

      assertEquals("Medium", model.value("BLOOM_STRENGTH", false), "0.12 是第三档");
      assertEquals("0.12", model.value("BLOOM_STRENGTH", true));
   }

   @Test
   void aPlainNumberReadsAsItsShortestDecimal() {
      PackSettingsModel model = model();

      assertEquals("128", model.value("SHADOW_DISTANCE", true));
      assertEquals("0.5", model.value("MATERIAL_SHEEN", true), "同一档在基础视图里是梯形标签，在高级视图里是原值");
      assertEquals("Medium", model.value("MATERIAL_SHEEN", false));
   }

   @Test
   void namesComeFromTheManifestUnlessTheyAreOverridden() {
      PackSettingsModel model = model();

      assertEquals("Shadows", model.name("SHADOW_QUALITY"), "界面里改过名字，不能再显示清单里的标签");
      assertEquals("Clouds", model.name("CLOUD_QUALITY"));
      assertEquals("Color Style", model.name("COLOR_GRADE"), "没改过的键要用清单里的 label");
   }

   @Test
   void groupsFollowTheKeyPrefixes() {
      PackSettingsModel model = model();

      assertEquals("Water", model.group("WATER_DENSITY"));
      assertEquals("Sky & atmosphere", model.group("CLOUD_COVERAGE"));
      assertEquals("Sky & atmosphere", model.group("SUN_SIZE"));
      assertEquals("Lighting", model.group("SHADOW_DISTANCE"));
      assertEquals("Lighting", model.group("LIGHT_WARMTH"));
      assertEquals("Lighting", model.group("AMBIENT_OCCLUSION"));
      assertEquals("Color & effects", model.group("TM_CONTRAST"));
   }

   @Test
   void everyHelpLineEndsWithTheApplyReminder() {
      PackSettingsModel model = model();

      assertTrue(model.help("SHADOW_QUALITY").startsWith("Shadows from terrain"));
      assertTrue(model.help("SHADOW_QUALITY").endsWith(" Changes take effect when you press Apply."));
      // 没有专门写帮助的键走默认那句，用的是显示名而不是清单标签。
      assertTrue(model.help("CLOUD_COVERAGE").startsWith("Adjust "));
      assertTrue(model.help("CLOUD_COVERAGE").endsWith(" Changes take effect when you press Apply."));
   }

   // ---------------------------------------------------------------- 键列表

   @Test
   void theBasicViewListsExactlyTheKnownSubsetInItsOwnOrder() {
      List<String> keys = model().keys(false);

      assertEquals(PackSettingsModel.BASIC, keys, "内置包声明了全部基础选项，顺序就是 BASIC 的顺序");
   }

   @Test
   void aManifestWithNoneOfTheBasicOptionsFallsBackToEveryDefinition() {
      List<String> keys = minimalModel("{\"FOO\": {\"label\": \"Foo\", \"default\": 1, \"values\": [0, 1]}}").keys(false);

      assertEquals(List.of("FOO"), keys, "一个基础选项都没有时不能显示空面板");
   }

   @Test
   void theAdvancedViewListsEveryDefinitionGroupedAndSorted() {
      PackSettingsModel model = model();
      List<String> keys = model.keys(true);

      assertEquals(model.graph.optionDefinitions().size(), keys.size());

      List<String> groups = new ArrayList<>();

      for (String key : keys) {
         if (groups.isEmpty() || !groups.getLast().equals(model.group(key))) {
            groups.add(model.group(key));
         }
      }

      assertEquals(List.of("Color & effects", "Lighting", "Sky & atmosphere", "Water"), groups, "先按分组、再按显示名排序");
   }

   // ---------------------------------------------------------------- 脏与重置

   @Test
   void editingMarksTheModelDirtyAndApplyingClearsIt() {
      PackSettingsModel model = model();
      assertFalse(model.dirty(), "刚打开时没有未应用的改动");

      model.change("SHADOW_QUALITY", false);
      assertTrue(model.dirty());

      model.applied();
      assertFalse(model.dirty(), "应用之后草稿就是已生效的那份");
   }

   @Test
   void resetPutsEveryOptionBackToItsDeclaredDefault() {
      PackSettingsModel model = model();
      model.change("SHADOW_QUALITY", false);
      assertEquals("High", model.value("SHADOW_QUALITY", false));

      model.applied();
      assertFalse(model.dirty(), "已生效");

      model.reset();

      assertEquals("Medium", model.value("SHADOW_QUALITY", false), "回到声明里的 default");
      assertEquals("Realistic", model.value("COLOR_GRADE", false), "没动过的键也在重置范围里，且回到 default 而不是 Off");
      assertTrue(model.dirty(), "重置之后与已生效的那份不同，所以是一次未应用的改动");
   }

   @Test
   void aWaterSubOptionIsUnavailableWhileWaterIsOffAndComesBackWhenItIsOn() {
      PackSettingsModel model = model();
      assertTrue(model.available("WATER_REFLECTION_QUALITY"));

      model.change("WATER_ENABLED", false);
      assertFalse(model.available("WATER_REFLECTION_QUALITY"), "水关了，子选项要灰掉");

      model.change("WATER_ENABLED", false);
      assertTrue(model.available("WATER_REFLECTION_QUALITY"));
   }

   @Test
   void waterEnabledItselfIsAlwaysAvailable() {
      PackSettingsModel model = model();
      model.change("WATER_ENABLED", false);

      assertTrue(model.available("WATER_ENABLED"), "开关自己不能被自己灰掉");
   }

   @Test
   void onlyOptionsDeclaredAsZeroOrOneCountAsToggles() {
      PackSettingsModel model = model();

      assertTrue(model.toggle("WATER_ENABLED", false));
      assertTrue(model.toggle("ANTIALIASING", false));
      assertFalse(model.toggle("COLOR_GRADE", false));
      assertFalse(model.toggle("SHADOW_DISTANCE", false));
   }

   /** 探针式的一条：确保上面其余断言用的默认值确实是夹具声明的那几个。 */
   @Test
   void theFixtureDeclaresTheDefaultsTheseTestsRelyOn() {
      Map<String, Double> options = PackGraph.parse(REAL_MANIFEST).options();

      assertEquals(2.0, options.get("SHADOW_QUALITY"));
      assertEquals(2.0, options.get("CLOUD_QUALITY"));
      assertEquals(0.5, options.get("COLOR_GRADE"));
      assertEquals(1.0, options.get("WATER_ENABLED"));
      assertEquals(0.12, options.get("BLOOM_STRENGTH"));
   }
}
