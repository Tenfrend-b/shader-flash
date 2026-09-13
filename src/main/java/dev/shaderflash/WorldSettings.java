package dev.shaderflash;

import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Iris 的 {@link WorldRenderingSettings#INSTANCE} 是一个<b>全局单例</b>：材质 ID 表、AO 等级、
 * 顶点格式之类都塞在同一个对象里。Iris 自己的前提是“同一时刻只有一条管线”。
 *
 * <p>我们加载第二条管线时，它的构造函数会顺手改写这些全局值，把主管线的设置踩掉。
 * 所以在创建副管线前后做一次快照 / 还原，并清掉被顺带置上的 reloadRequired
 * （那个标志会让 Iris 在下一帧重建全部区块网格，代价很大）。
 */
public final class WorldSettings {
	private final Object2IntMap<BlockState> blockStateIds;
	private final Map<Block, RenderLayer> blockTypeIds;
	private final Object2IntFunction<NamespacedId> entityIds;
	private final Object2IntFunction<NamespacedId> itemIds;
	private final float ambientOcclusionLevel;
	private final boolean disableDirectionalShading;
	private final boolean useSeparateAo;
	private final boolean useExtendedVertexFormat;
	private final boolean separateEntityDraws;
	private final boolean voxelizeLightBlocks;

	private WorldSettings(WorldRenderingSettings source) {
		this.blockStateIds = source.getBlockStateIds();
		this.blockTypeIds = source.getBlockTypeIds();
		this.entityIds = source.getEntityIds();
		this.itemIds = source.getItemIds();
		this.ambientOcclusionLevel = source.getAmbientOcclusionLevel();
		this.disableDirectionalShading = source.shouldDisableDirectionalShading();
		this.useSeparateAo = source.shouldUseSeparateAo();
		this.useExtendedVertexFormat = source.shouldUseExtendedVertexFormat();
		this.separateEntityDraws = source.shouldSeparateEntityDraws();
		this.voxelizeLightBlocks = source.shouldVoxelizeLightBlocks();
	}

	public static WorldSettings capture() {
		return new WorldSettings(WorldRenderingSettings.INSTANCE);
	}

	public void restore() {
		WorldRenderingSettings target = WorldRenderingSettings.INSTANCE;
		target.setBlockStateIds(blockStateIds);
		target.setBlockTypeIds(blockTypeIds);
		if (entityIds != null) {
			target.setEntityIds(entityIds);
		}
		if (itemIds != null) {
			target.setItemIds(itemIds);
		}
		target.setAmbientOcclusionLevel(ambientOcclusionLevel);
		target.setDisableDirectionalShading(disableDirectionalShading);
		target.setUseSeparateAo(useSeparateAo);
		target.setUseExtendedVertexFormat(useExtendedVertexFormat);
		target.setSeparateEntityDraws(separateEntityDraws);
		target.setVoxelizeLightBlocks(voxelizeLightBlocks);
		// 上面这些 setter 只要发现值变了就会置 reloadRequired=true。全局值我们已经还原成
		// 主管线的那一份，所以不应该让 Iris 去重建世界。
		target.clearReloadRequired();
	}

	/**
	 * 把某个光影包的材质设置应用到全局。
	 *
	 * @param pipeline           新主管线，用来取“方向性明暗”这类只有管线自己知道的状态。
	 * @param triggerChunkReload 材质 ID 表发生变化时，是否允许 Iris 在下一帧重建区块网格。
	 *                           提升副管线为主管线时必须允许，否则区块里的旧材质 ID 会和新包的
	 *                           着色器对不上。
	 */
	public static void applyFor(ShaderPack pack, WorldRenderingPipeline pipeline, NamespacedId dimension,
								boolean triggerChunkReload) {
		WorldRenderingSettings target = WorldRenderingSettings.INSTANCE;
		PackDirectives directives = pack.getProgramSet(dimension).getPackDirectives();

		target.setEntityIds(pack.getIdMap().getEntityIdMap());
		target.setItemIds(pack.getIdMap().getItemIdMap());
		target.setAmbientOcclusionLevel(directives.getAmbientOcclusionLevel());
		// 主管线此刻可能还没构造出来（进世界 / Iris 刚重载时就是如此），
		// 那就按包自己的 directives 算：IrisRenderingPipeline#shouldDisableDirectionalShading()
		// 返回的就是 !directives.isOldLighting()。
		target.setDisableDirectionalShading(pipeline != null
			? pipeline.shouldDisableDirectionalShading()
			: !directives.isOldLighting());
		target.setUseSeparateAo(directives.shouldUseSeparateAo());
		target.setUseExtendedVertexFormat(true);
		target.setSeparateEntityDraws(directives.shouldUseSeparateEntityDraws());
		target.setVoxelizeLightBlocks(directives.shouldVoxelizeLightBlocks());
		target.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
			pack.getIdMap().getBlockProperties()));
		target.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(
			pack.getIdMap().getBlockRenderTypeMap()));

		if (!triggerChunkReload) {
			target.clearReloadRequired();
		}
	}

	/** 方便比较两条管线是否需要重建区块网格。 */
	public static boolean sameBlockIds(WorldSettings a, WorldSettings b) {
		if (a.blockStateIds == null || b.blockStateIds == null) {
			return a.blockStateIds == b.blockStateIds;
		}
		return a.blockStateIds.equals(b.blockStateIds);
	}

	public Object2IntMap<BlockState> blockStateIds() {
		return blockStateIds;
	}

	/**
	 * 只记录那些**会被烘焙进区块网格**的全局设置。
	 *
	 * <p>Iris 的 {@code reloadRequired} 是个很粗的开关：只要它管的任意一项变了就会置位，
	 * 然后调用方往往会直接 {@code allChanged()}（把所有区块网格丢掉重建），玩家看到的就是
	 * 一片区块"卸载又重载"。这里把真正影响网格的那几项单独挑出来，只有它们变了才需要重建；
	 * 其余（实体分离绘制之类）换了也不动网格。
	 */
	public static final class MeshState {
		private final Object2IntMap<BlockState> blockStateIds;
		private final Map<Block, RenderLayer> blockTypeIds;
		private final boolean extendedVertexFormat;
		private final float ambientOcclusionLevel;
		private final boolean separateAo;
		private final boolean directionalShading;
		private final boolean voxelizeLightBlocks;

		private MeshState(WorldRenderingSettings settings) {
			this.blockStateIds = settings.getBlockStateIds();
			this.blockTypeIds = settings.getBlockTypeIds();
			this.extendedVertexFormat = settings.shouldUseExtendedVertexFormat();
			this.ambientOcclusionLevel = settings.getAmbientOcclusionLevel();
			this.separateAo = settings.shouldUseSeparateAo();
			this.directionalShading = !settings.shouldDisableDirectionalShading();
			this.voxelizeLightBlocks = settings.shouldVoxelizeLightBlocks();
		}

		public static MeshState capture() {
			return new MeshState(WorldRenderingSettings.INSTANCE);
		}

		/** 返回人类可读的差异清单；空表示现有网格可以直接沿用，不需要重建。 */
		public List<String> differences(MeshState other) {
			List<String> differences = new ArrayList<>();
			if (!Objects.equals(blockStateIds, other.blockStateIds)) {
				differences.add("方块材质 ID 表");
			}
			if (!Objects.equals(blockTypeIds, other.blockTypeIds)) {
				differences.add("方块渲染类型表");
			}
			if (extendedVertexFormat != other.extendedVertexFormat) {
				differences.add("扩展顶点格式");
			}
			if (Float.compare(ambientOcclusionLevel, other.ambientOcclusionLevel) != 0) {
				differences.add("环境光遮蔽等级");
			}
			if (separateAo != other.separateAo) {
				differences.add("分离 AO");
			}
			if (directionalShading != other.directionalShading) {
				differences.add("方向性明暗");
			}
			if (voxelizeLightBlocks != other.voxelizeLightBlocks) {
				differences.add("体素化光源方块");
			}
			return differences;
		}

		/** 顶点格式变了就必须走硬重建：旧网格的顶点布局已经对不上新格式。 */
		public boolean requiresHardReload(MeshState other) {
			return extendedVertexFormat != other.extendedVertexFormat;
		}

		/**
		 * 方块 ID 表 / 渲染类型表是否变了。
		 *
		 * <p>这两张表既要写进区块顶点，也会被 Iris 的 Sodium 兼容层在
		 * {@code ChunkBuildBuffers} 构造时**捕获一份**（见 {@code BlockContextHolderMixin}）。
		 * 在拿不到那份捕获表的场合，只能靠整世界重载来刷新，所以这里单独暴露出来供调用方判断。
		 */
		public boolean requiresChunkBlockIdRefresh(MeshState other) {
			return !Objects.equals(blockStateIds, other.blockStateIds)
				|| !Objects.equals(blockTypeIds, other.blockTypeIds);
		}

		/**
		 * 是否有设置被 Iris <b>烘焙进了着色器源码</b>，从而必须让地形程序重编一次。
		 *
		 * <p>Iris 的 {@code SodiumTransformer.injectVertInit} 会读
		 * {@code WorldRenderingSettings.shouldUseSeparateAo()} 并把它直接拼进地形着色器源码
		 * （{@code a_Color} 取 {@code a_Color} 还是 {@code vec4(a_Color.rgb * a_Color.a, 1.0)}）。
		 * 也就是说这个值在**编译期**就固定了：切换主副之后光重建区块网格不够，
		 * 缓存里的地形程序仍然带着旧包的取值，顶点数据与着色器会对不上。
		 *
		 * <p>修法是切换时 bump 一次 {@code versionCounterForSodiumShaderReload}，
		 * 让 Iris 的 {@code IrisChunkProgramOverrides} 丢掉地形程序并在新取值下重编。
		 */
		public boolean requiresShaderRecompile(MeshState other) {
			return separateAo != other.separateAo;
		}
	}
}
