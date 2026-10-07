package com.caldera.shaders.graph;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@code CalderaFrame} 那一段 std140 的布局：**只在这里声明一次**。
 * <p>
 * 迁移前同一份布局在三个地方各写了一遍：{@code GraphFrame.MEMBERS} 是一份成员清单（而且**没有人读它**
 * ——{@code declaration()} 自己又抄了一份），{@code declaration()} 是第二份，而 {@code upload()} 里
 * 的字节偏移（0、64、128……576、640）与 {@code environment[]} 的 36 个下标是第三份。三者只要有一处
 * 漂移，表现都是**安静地画错一帧**：着色器读到的是隔壁字段的值，既不抛异常也不进日志。
 * <p>
 * 现在字段按 GLSL 里的顺序声明一次，偏移由 {@link #size} 那段走法**算出来**（mat4 = 64、vec4 = 16、
 * {@code mat4[6]} = 384，全部 16 字节对齐——这份布局简单到不会踩 std140 的坑）。写入侧不再写魔数：
 * {@code upload()} 用 {@link #offset}，那 36 个 float 的槽位用 {@link #slot}。
 * <p>
 * <b>纠错靠的是金样测试</b>（{@code FrameLayoutTest}）：生成的 GLSL 文本与冻结的那一段逐字对比
 * （忽略空白）、{@link #size} 必须是 1072、23 个偏移逐一对上、9 个槽位逐一对上。算错了当场红，
 * 而不是等门禁截出一张颜色不对的图。
 * <p>
 * <b>生成的文本与原件有一处刻意的差别：换行与缩进。</b>原件是手写的 7 行（最后两行还带着 6 个空格的
 * 缩进），这里一行一个字段。GLSL 在词法上不区分这两者，字段名、类型、顺序与总数逐字相同——但它是本次
 * 唯一一处"编译出的源码文本变了"的地方，记在这里而不是藏着。
 */
final class FrameLayout {

   /**
    * 一个字段。{@code declaration} 是它在 GLSL 里的那一行（含结尾的分号），{@code bytes} 是它占的
    * 字节数；两者放在一起，是因为它们说的是同一件事的两种单位。
    */
   enum Field {
      PROJECTION("mat4 Projection;", 64),
      VIEW("mat4 View;", 64),
      INVERSE_PROJECTION("mat4 InverseProjection;", 64),
      INVERSE_VIEW("mat4 InverseView;", 64),
      PREVIOUS_PROJECTION("mat4 PreviousProjection;", 64),
      PREVIOUS_VIEW("mat4 PreviousView;", 64),
      CAMERA_DELTA_AND_HISTORY_VALID("vec4 CameraDeltaAndHistoryValid;", 16),
      TIME_DELTA_FRAME("vec4 TimeDeltaFrame;", 16),
      VIEW_SIZE_AND_INVERSE("vec4 ViewSizeAndInverse;", 16),
      /** 从这里开始是 {@code environment[]} 那 36 个 float——连续 9 个 vec4，见 {@link #slot}。 */
      WORLD_TIME_WEATHER_DIMENSION("vec4 WorldTimeWeatherDimension;", 16),
      SUN_DIRECTION_AND_RAIN_BRIGHTNESS("vec4 SunDirectionAndRainBrightness;", 16),
      MOON_DIRECTION_AND_PHASE("vec4 MoonDirectionAndPhase;", 16),
      CAMERA_POSITION_HIGH_AND_FOG_TYPE("vec4 CameraPositionHighAndFogType;", 16),
      CAMERA_POSITION_LOW_AND_FAR_PLANE("vec4 CameraPositionLowAndFarPlane;", 16),
      FOG_COLOR_AND_START("vec4 FogColorAndStart;", 16),
      FOG_DISTANCES("vec4 FogDistances;", 16),
      SKY_COLOR_AND_STAR_BRIGHTNESS("vec4 SkyColorAndStarBrightness;", 16),
      CLOUD_OFFSET_AND_GAME_TIME("vec4 CloudOffsetAndGameTime;", 16),
      INVERSE_HAND_PROJECTION("mat4 InverseHandProjection;", 64),
      HAND_PROJECTION_VALID("vec4 HandProjectionValid;", 16),
      /** 下面三个由 {@code HeldLight.write} **顺序**写：表只决定它的起点。 */
      HELD_LIGHT_POSITION_RADIUS("vec4 HeldLightPositionRadius;", 16),
      HELD_LIGHT_COLOR("vec4 HeldLightColor;", 16),
      HELD_LIGHT_VIEW_PROJECTION("mat4 HeldLightViewProjection[6];", 384);

      private final String declaration;
      private final int bytes;

      Field(String declaration, int bytes) {
         this.declaration = declaration;
         this.bytes = bytes;
      }
   }

   /** {@code environment[]} 的槽位数：就是那 9 个 vec4。 */
   static final int ENVIRONMENT_SLOTS = 36;

   private static final Map<Field, Integer> OFFSETS = new EnumMap<>(Field.class);
   private static final Map<Field, Integer> SLOTS = new EnumMap<>(Field.class);
   private static final int SIZE;

   static {
      int cursor = 0;
      for (Field field : Field.values()) {
         OFFSETS.put(field, cursor);
         if (field.ordinal() >= Field.WORLD_TIME_WEATHER_DIMENSION.ordinal()
               && field.ordinal() <= Field.CLOUD_OFFSET_AND_GAME_TIME.ordinal()) {
            SLOTS.put(field, (cursor - OFFSETS.get(Field.WORLD_TIME_WEATHER_DIMENSION)) / Float.BYTES);
         }

         cursor += field.bytes;
      }

      SIZE = cursor;
   }

   private FrameLayout() {
   }

   /** 整段占多少字节。 */
   static int size() {
      return SIZE;
   }

   /** 某个字段的字节偏移。 */
   static int offset(Field field) {
      return OFFSETS.get(field);
   }

   /**
    * 某个字段在 {@code environment[]} 里的起始槽位。
    * <p>
    * 只对那 9 个连续的 vec4 有意义（它们就是那个数组），其余字段调用会**大声失败**——把 {@code mat4}
    * 的偏移当成槽位用是一类安静的错。
    */
   static int slot(Field field) {
      Integer slot = SLOTS.get(field);
      if (slot == null) {
         throw new IllegalArgumentException(field + " is not part of the environment[] block");
      }

      return slot;
   }

   /** GLSL 里那一段成员声明，一行一个字段。 */
   static String block() {
      StringBuilder text = new StringBuilder();

      for (Field field : Field.values()) {
         text.append(field.declaration).append('\n');
      }

      return text.toString();
   }
}
