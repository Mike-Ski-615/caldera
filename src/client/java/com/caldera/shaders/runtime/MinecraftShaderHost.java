package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.graph.GraphRenderer;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.pack.LegacyPackMigration;
import com.caldera.shaders.pack.ShaderPackScanner;
import com.caldera.shaders.render.shadow.DirectionalShadowPipelines;
import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;
import com.caldera.shaders.render.shadow.SodiumShadowTerrainRenderer;
import com.caldera.shaders.render.shadow.SodiumTerrainShadowPipelines;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * {@link ShaderHost} 的生产实现：把每一项都原样委托给 Minecraft 与 0.5.1 原作里的那些静态入口。
 * <p>
 * 这个类的唯一职责就是"不改变任何语义地转发"。里面的每个方法体都应当与迁移前
 * {@code ShaderRuntime} 中对应那一行逐字一致；如果哪天需要对不上了，那就是行为偏离。
 */
public final class MinecraftShaderHost implements ShaderHost {

	@Override
	public ShaderConfig loadConfig() {
		return ShaderConfig.load();
	}

	@Override
	public void saveConfig(ShaderConfig config) {
		config.save();
	}

	@Override
	public void ensurePackDirectory() {
		ShaderPackScanner.ensureShaderPackDirectory();
	}

	@Override
	public ShaderPackScanner.ScanResult scanPacks() {
		return ShaderPackScanner.scan();
	}

	@Override
	public boolean vulkanActive() {
		return BackendStatus.vulkanActive();
	}

	@Override
	public boolean detachLegacyPack() {
		return LegacyPackMigration.detach(Minecraft.getInstance());
	}

	@Override
	public void saveOptions() {
		Minecraft.getInstance().options.save();
	}

	@Override
	public CompletableFuture<Void> reloadResources() {
		return Minecraft.getInstance().reloadResourcePacks();
	}

	@Override
	public Executor clientThread() {
		return Minecraft.getInstance();
	}

	@Override
	public PreparedRenderer prepareRenderer(ShaderConfig config) throws Exception {
		GraphRenderer renderer = NativePackRuntime.prepare(config);

		// 即使 renderer 为 null 也要给出句柄：原件在禁用态下依然调用 activate(null, id)，
		// 而那一步会拆除当前生效的渲染器。
		return new PreparedRenderer() {
			@Override
			public void activate(String packId) {
				NativePackRuntime.activate(renderer, packId);
			}

			@Override
			public void close() {
				if (renderer != null) {
					renderer.close();
				}
			}
		};
	}

	@Override
	public void closeRenderer() {
		NativePackRuntime.close();
	}

	@Override
	public void closeReloadableResources() {
		DirectionalShadowPipelines.close();
		DirectionalShadowRenderer.close();
		SodiumShadowTerrainRenderer.close();
		SodiumTerrainShadowPipelines.close();
	}

	@Override
	public void requestGeometryRebuild() {
		NativePackRuntime.requestGeometryRebuild();
	}

	@Override
	public String rendererFailure() {
		return NativePackRuntime.failure();
	}
}
