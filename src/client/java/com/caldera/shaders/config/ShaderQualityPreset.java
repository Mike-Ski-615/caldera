package com.caldera.shaders.config;

/**
 * 阴影质量档位。
 * <p>
 * 它同时是**下标**（{@code values()[quality]}，质量在包里就是一个 0..4 的数）与**名字**（{@code OFF}
 * 表示关着），而 {@code enabled()} 就是后者。
 * <p>
 * 迁移前还带着一套没人用的第二词汇表：两个字段（序列化名与层级号）、两个访问器、一个
 * {@code displayName()} 与一个 {@code fromSerializedName()}，外加一个合成出来的 {@code $values()}。
 * 一个调用者都没有——档位从来不是从字符串读出来的，而是下标。删掉它们之后这个 enum 只剩真实存在的
 * 那两面。
 */
public enum ShaderQualityPreset {
   OFF,
   LOW,
   MEDIUM,
   HIGH,
   ULTRA;

   public boolean enabled() {
      return this != OFF;
   }
}
