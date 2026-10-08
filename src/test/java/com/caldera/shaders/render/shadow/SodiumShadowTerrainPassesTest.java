package com.caldera.shaders.render.shadow;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阴影关卡在 Sodium 那边用哪几条 {@link TerrainRenderPass}。
 * <p>
 * 这三张表都是纯函数、不需要 GPU，但迁移前没有任何东西钉住它们，而它们各自承载一条**错了不会报错**的
 * 决定：{@link SodiumShadowTerrainPasses#terrainPasses} 决定某个级联是画"合并的不透明批"还是分两次
 * 画；{@link SodiumShadowTerrainPasses#source} 决定阴影 pass 该取原版哪一份地形数据；
 * {@link SodiumShadowTerrainPasses#passesFor} 的顺序就是 Sodium 的 pass 序号，顺序错了会把某一批
 * 画到别的 pass 上去。
 * <p>
 * 顺带钉住的是"第一个级联从不合并"这条：合并是给**最远**那层省一次绘制的，而级联 0 是最近的一层，
 * 原件用 {@code cascade > 0} 把它排除在外。这不是笔误，是这里唯一需要读者知道的反直觉之处。
 */
class SodiumShadowTerrainPassesTest {

	private static List<TerrainRenderPass> of(TerrainRenderPass[] passes) {
		return List.of(passes);
	}

	@Test
	void theFarthestCascadeCombinesItsOpaquePasses() {
		// 级联 1 是两层里的最后一层：合并成一次绘制。
		assertEquals(List.of(SodiumShadowTerrainPasses.combined(1)), of(SodiumShadowTerrainPasses.terrainPasses(1, 2)));
		assertEquals(List.of(SodiumShadowTerrainPasses.combined(2)), of(SodiumShadowTerrainPasses.terrainPasses(2, 3)));
	}

	@Test
	void theFirstCascadeNeverCombinesEvenWhenItIsTheLast() {
		// cascade > 0 这一条守卫：只有一层级联时，级联 0 仍然分两次画。
		assertEquals(List.of(SodiumShadowTerrainPasses.solid(0), SodiumShadowTerrainPasses.cutout(0)),
				of(SodiumShadowTerrainPasses.terrainPasses(0, 1)));
	}

	@Test
	void aCascadeThatIsNotLastDrawsSolidAndCutoutSeparately() {
		assertEquals(List.of(SodiumShadowTerrainPasses.solid(1), SodiumShadowTerrainPasses.cutout(1)),
				of(SodiumShadowTerrainPasses.terrainPasses(1, 3)));
	}

	@Test
	void everyShadowPassMapsBackToTheOriginalPassItReplaces() {
		for(int cascade = 0; cascade < 4; ++cascade) {
			assertSame(DefaultTerrainRenderPasses.SOLID, SodiumShadowTerrainPasses.source(SodiumShadowTerrainPasses.solid(cascade)));
			assertSame(DefaultTerrainRenderPasses.CUTOUT, SodiumShadowTerrainPasses.source(SodiumShadowTerrainPasses.cutout(cascade)));
			// 合并的那一批吃的是不透明数据（它把 solid 与 cutout 的存储各喂一次）。
			assertSame(DefaultTerrainRenderPasses.SOLID, SodiumShadowTerrainPasses.source(SodiumShadowTerrainPasses.combined(cascade)));
		}

		assertSame(DefaultTerrainRenderPasses.SOLID, SodiumShadowTerrainPasses.source(SodiumShadowTerrainPasses.LOCAL_SOLID));
		assertSame(DefaultTerrainRenderPasses.CUTOUT, SodiumShadowTerrainPasses.source(SodiumShadowTerrainPasses.LOCAL_CUTOUT));
		// 不是我们的 pass 就原样返回——Sodium 自己还有别的沿用不上。
		assertSame(DefaultTerrainRenderPasses.TRANSLUCENT, SodiumShadowTerrainPasses.source(DefaultTerrainRenderPasses.TRANSLUCENT));
	}

	@Test
	void theShadowPassOrderIsTheOneSodiumNumbersThemBy() {
		assertEquals(List.of(
				SodiumShadowTerrainPasses.solid(0),
				SodiumShadowTerrainPasses.solid(1),
				SodiumShadowTerrainPasses.solid(2),
				SodiumShadowTerrainPasses.solid(3),
				SodiumShadowTerrainPasses.LOCAL_SOLID,
				SodiumShadowTerrainPasses.combined(0),
				SodiumShadowTerrainPasses.combined(1),
				SodiumShadowTerrainPasses.combined(2),
				SodiumShadowTerrainPasses.combined(3)),
				of(SodiumShadowTerrainPasses.passesFor(DefaultTerrainRenderPasses.SOLID)));

		assertEquals(List.of(
				SodiumShadowTerrainPasses.cutout(0),
				SodiumShadowTerrainPasses.cutout(1),
				SodiumShadowTerrainPasses.cutout(2),
				SodiumShadowTerrainPasses.cutout(3),
				SodiumShadowTerrainPasses.LOCAL_CUTOUT,
				SodiumShadowTerrainPasses.combined(0),
				SodiumShadowTerrainPasses.combined(1),
				SodiumShadowTerrainPasses.combined(2),
				SodiumShadowTerrainPasses.combined(3)),
				of(SodiumShadowTerrainPasses.passesFor(DefaultTerrainRenderPasses.CUTOUT)));
	}

	@Test
	void anUnrelatedPassHasNoShadowPasses() {
		assertEquals(0, SodiumShadowTerrainPasses.passesFor(DefaultTerrainRenderPasses.TRANSLUCENT).length);
		assertEquals(0, SodiumShadowTerrainPasses.passesFor(SodiumShadowTerrainPasses.combined(0)).length);
	}

	@Test
	void onlyTheCombinedTableIsCombined() {
		for(int cascade = 0; cascade < 4; ++cascade) {
			assertTrue(SodiumShadowTerrainPasses.isCombined(SodiumShadowTerrainPasses.combined(cascade)));
			assertFalse(SodiumShadowTerrainPasses.isCombined(SodiumShadowTerrainPasses.solid(cascade)));
			assertFalse(SodiumShadowTerrainPasses.isCombined(SodiumShadowTerrainPasses.cutout(cascade)));
		}

		assertFalse(SodiumShadowTerrainPasses.isCombined(SodiumShadowTerrainPasses.LOCAL_SOLID));
		assertFalse(SodiumShadowTerrainPasses.isCombined(SodiumShadowTerrainPasses.LOCAL_CUTOUT));
	}

	@Test
	void theTablesAreStableIdentityObjects() {
		// 这几张表是靠身份比较的（Sodium 的 pass 序号、region 的批缓存都按引用找），所以同一个下标
		// 必须每次拿到同一个对象，而不同表之间必须是不同对象。
		assertSame(SodiumShadowTerrainPasses.solid(2), SodiumShadowTerrainPasses.solid(2));
		assertNotSame(SodiumShadowTerrainPasses.solid(2), SodiumShadowTerrainPasses.solid(3));
		assertNotSame(SodiumShadowTerrainPasses.solid(2), SodiumShadowTerrainPasses.cutout(2));
		assertNotSame(SodiumShadowTerrainPasses.solid(2), SodiumShadowTerrainPasses.combined(2));
		assertNotSame(SodiumShadowTerrainPasses.LOCAL_SOLID, SodiumShadowTerrainPasses.LOCAL_CUTOUT);
	}
}
