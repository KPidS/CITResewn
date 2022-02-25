package shcm.shsupercm.fabric.citresewn.defaults.cit.types;

import com.mojang.blaze3d.systems.RenderSystem;
import io.shcm.shsupercm.fabric.fletchingtable.api.Entrypoint;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3f;
import org.lwjgl.opengl.GL11;
import shcm.shsupercm.fabric.citresewn.api.CITTypeContainer;
import shcm.shsupercm.fabric.citresewn.cit.*;
import shcm.shsupercm.fabric.citresewn.defaults.config.CITResewnDefaultsConfig;
import shcm.shsupercm.fabric.citresewn.defaults.mixin.types.enchantment.BufferBuilderStorageAccessor;
import shcm.shsupercm.fabric.citresewn.defaults.mixin.types.enchantment.RenderPhaseAccessor;
import shcm.shsupercm.fabric.citresewn.ex.CITParsingException;
import shcm.shsupercm.fabric.citresewn.pack.format.PropertyGroup;
import shcm.shsupercm.fabric.citresewn.pack.format.PropertyKey;
import shcm.shsupercm.fabric.citresewn.pack.format.PropertyValue;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Consumer;

import static com.mojang.blaze3d.systems.RenderSystem.*;
import static org.lwjgl.opengl.GL11.*;

public class TypeEnchantment extends CITType {
    @Entrypoint(CITTypeContainer.ENTRYPOINT)
    public static final Container CONTAINER = new Container();

    @Override
    public Set<PropertyKey> typeProperties() {
        return Set.of(
                PropertyKey.of("texture"),
                PropertyKey.of("layer"),
                PropertyKey.of("speed"),
                PropertyKey.of("rotation"),
                PropertyKey.of("duration"),
                PropertyKey.of("r"),
                PropertyKey.of("g"),
                PropertyKey.of("b"),
                PropertyKey.of("a"),
                PropertyKey.of("useGlint"),
                PropertyKey.of("blur"),
                PropertyKey.of("blend"));
    }

    public Identifier texture;
    public int layer;
    public float speed, rotation, duration, r, g, b, a;
    public boolean useGlint, blur;
    public Blend blend;

    public final Map<GlintRenderLayer, RenderLayer> renderLayers = new EnumMap<>(GlintRenderLayer.class);
    private final WrappedMethodIntensity methodIntensity = new WrappedMethodIntensity();

    @Override
    public void load(List<CITCondition> conditions, PropertyGroup properties, ResourceManager resourceManager) throws CITParsingException {
        PropertyValue textureProp = properties.getLastWithoutMetadata("citresewn", "texture");
        this.texture = resolveAsset(properties.identifier, textureProp, "textures", ".png", resourceManager);
        if (this.texture == null)
            throw textureProp == null ? new CITParsingException("No texture specified", properties, -1) : new CITParsingException("Could not resolve texture", properties, textureProp.position());

        PropertyValue layerProp = properties.getLastWithoutMetadataOrDefault("0", "citresewn", "layer");
        try {
            this.layer = Integer.parseInt(layerProp.value());
        } catch (Exception e) {
            throw new CITParsingException("Could not parse integer", properties, layerProp.position(), e);
        }

        this.speed = parseFloatOrDefault(1f, "speed", properties);
        this.rotation = parseFloatOrDefault(10f, "rotation", properties);
        this.duration = Math.max(0f, parseFloatOrDefault(0f, "duration", properties));
        this.r = Math.max(0f, parseFloatOrDefault(1f, "r", properties));
        this.g = Math.max(0f, parseFloatOrDefault(1f, "g", properties));
        this.b = Math.max(0f, parseFloatOrDefault(1f, "b", properties));
        this.a = Math.max(0f, parseFloatOrDefault(1f, "a", properties));

        this.useGlint = Boolean.parseBoolean(properties.getLastWithoutMetadataOrDefault("false", "citresewn", "useGlint").value());
        this.blur = Boolean.parseBoolean(properties.getLastWithoutMetadataOrDefault("true", "citresewn", "blur").value());

        PropertyValue blendProp = properties.getLastWithoutMetadataOrDefault("add", "citresewn", "blend");
        try {
            this.blend = Blend.getBlend(blendProp.value());
        } catch (Exception e) {
            throw new CITParsingException("Could not parse blending method", properties, blendProp.position(), e);
        }
    }

    private float parseFloatOrDefault(float defaultValue, String propertyName, PropertyGroup properties) throws CITParsingException {
        PropertyValue property = properties.getLastWithoutMetadata("citresewn", propertyName);
        if (property == null)
            return defaultValue;
        try {
            return Float.parseFloat(property.value());
        } catch (Exception e) {
            throw new CITParsingException("Could not parse float", properties, property.position(), e);
        }
    }

    public static class Container extends CITTypeContainer<TypeEnchantment> {
        public Container() {
            super(TypeEnchantment.class, TypeEnchantment::new, "enchantment");
        }

        public List<CIT<TypeEnchantment>> loaded = new ArrayList<>();
        public List<List<CIT<TypeEnchantment>>> loadedLayered = new ArrayList<>();

        private List<CIT<TypeEnchantment>> appliedContext = null;
        private boolean apply = false, defaultGlint = false;

        @Override
        public void load(List<CIT<TypeEnchantment>> parsedCITs) {
            loaded.addAll(parsedCITs);

            Map<Integer, List<CIT<TypeEnchantment>>> layers = new HashMap<>();
            for (CIT<TypeEnchantment> cit : loaded)
                layers.computeIfAbsent(cit.type.layer, i -> new ArrayList<>()).add(cit);
            loadedLayered.clear();
            layers.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEachOrdered(layer -> loadedLayered.add(layer.getValue()));

            for (CIT<TypeEnchantment> cit : loaded)
                for (GlintRenderLayer glintLayer : GlintRenderLayer.values()) {
                    RenderLayer renderLayer = glintLayer.build(cit.type, cit.propertiesIdentifier);

                    cit.type.renderLayers.put(glintLayer, renderLayer);
                    ((BufferBuilderStorageAccessor) MinecraftClient.getInstance().getBufferBuilders()).getEntityBuilders().put(renderLayer, new BufferBuilder(renderLayer.getExpectedBufferSize()));
                }
        }

        @Override
        public void dispose() {
            appliedContext = null;

            for (CIT<TypeEnchantment> cit : loaded)
                for (RenderLayer renderLayer : cit.type.renderLayers.values())
                    ((BufferBuilderStorageAccessor) MinecraftClient.getInstance().getBufferBuilders()).getEntityBuilders().remove(renderLayer);

            loaded.clear();
            loadedLayered.clear();
        }

        public void apply() {
            if (appliedContext != null)
                apply = true;
        }

        public boolean shouldApply() {
            return apply;
        }

        public boolean shouldNotApplyDefaultGlint() {
            return apply && !defaultGlint;
        }

        public Container setContext(CITContext context) {
            apply = false;
            defaultGlint = false;
            appliedContext = null;
            if (context == null)
                return this;

            List<WeakReference<CIT<TypeEnchantment>>> cits = ((CITCacheEnchantment) (Object) context.stack).citresewn$getCacheTypeEnchantment().get(context);

            appliedContext = new ArrayList<>();
            if (cits != null)
                for (WeakReference<CIT<TypeEnchantment>> citRef : cits)
                    if (citRef != null) {
                        CIT<TypeEnchantment> cit = citRef.get();
                        if (cit != null) {
                            appliedContext.add(cit);
                            if (cit.type.useGlint)
                                defaultGlint = true;
                        }
                    }

            if (appliedContext.isEmpty())
                appliedContext = null;

            return this;
        }

        public List<CIT<TypeEnchantment>> getRealTimeCIT(CITContext context) {
            List<CIT<TypeEnchantment>> cits = new ArrayList<>();
            for (List<CIT<TypeEnchantment>> layer : loadedLayered)
                for (CIT<TypeEnchantment> cit : layer)
                    if (cit.test(context)) {
                        cits.add(cit);
                        break;
                    }

            return cits;
        }
    }
    
    public enum GlintRenderLayer {
        ARMOR_GLINT("armor_glint", 8f, layer -> layer
                .shader(RenderPhaseAccessor.ARMOR_GLINT_SHADER())
                .writeMaskState(RenderPhaseAccessor.COLOR_MASK())
                .cull(RenderPhaseAccessor.DISABLE_CULLING())
                .depthTest(RenderPhaseAccessor.EQUAL_DEPTH_TEST())
                .layering(RenderPhaseAccessor.VIEW_OFFSET_Z_LAYERING())),
        ARMOR_ENTITY_GLINT("armor_entity_glint", 0.16f, layer -> layer
                .shader(RenderPhaseAccessor.ARMOR_ENTITY_GLINT_SHADER())
                .writeMaskState(RenderPhaseAccessor.COLOR_MASK())
                .cull(RenderPhaseAccessor.DISABLE_CULLING())
                .depthTest(RenderPhaseAccessor.EQUAL_DEPTH_TEST())
                .layering(RenderPhaseAccessor.VIEW_OFFSET_Z_LAYERING())),
        GLINT_TRANSLUCENT("glint_translucent", 8f, layer -> layer
                .shader(RenderPhaseAccessor.TRANSLUCENT_GLINT_SHADER())
                .writeMaskState(RenderPhaseAccessor.COLOR_MASK())
                .cull(RenderPhaseAccessor.DISABLE_CULLING())
                .depthTest(RenderPhaseAccessor.EQUAL_DEPTH_TEST())
                .target(RenderPhaseAccessor.ITEM_TARGET())),
        GLINT("glint", 8f, layer -> layer
                .shader(RenderPhaseAccessor.GLINT_SHADER())
                .writeMaskState(RenderPhaseAccessor.COLOR_MASK())
                .cull(RenderPhaseAccessor.DISABLE_CULLING())
                .depthTest(RenderPhaseAccessor.EQUAL_DEPTH_TEST())),
        DIRECT_GLINT("glint_direct", 8f, layer -> layer
                .shader(RenderPhaseAccessor.DIRECT_GLINT_SHADER())
                .writeMaskState(RenderPhaseAccessor.COLOR_MASK())
                .cull(RenderPhaseAccessor.DISABLE_CULLING())
                .depthTest(RenderPhaseAccessor.EQUAL_DEPTH_TEST())),
        ENTITY_GLINT("entity_glint", 0.16f, layer -> layer
                .shader(RenderPhaseAccessor.ENTITY_GLINT_SHADER())
                .writeMaskState(RenderPhaseAccessor.COLOR_MASK())
                .cull(RenderPhaseAccessor.DISABLE_CULLING())
                .depthTest(RenderPhaseAccessor.EQUAL_DEPTH_TEST())
                .target(RenderPhaseAccessor.ITEM_TARGET())),
        DIRECT_ENTITY_GLINT("entity_glint_direct", 0.16f, layer -> layer
                .shader(RenderPhaseAccessor.DIRECT_ENTITY_GLINT_SHADER())
                .writeMaskState(RenderPhaseAccessor.COLOR_MASK())
                .cull(RenderPhaseAccessor.DISABLE_CULLING())
                .depthTest(RenderPhaseAccessor.EQUAL_DEPTH_TEST()));

        public final String name;
        private final Consumer<RenderLayer.MultiPhaseParameters.Builder> setup;
        private final float scale;

        GlintRenderLayer(String name, float scale, Consumer<RenderLayer.MultiPhaseParameters.Builder> setup) {
            this.name = name;
            this.scale = scale;
            this.setup = setup;
        }

        public RenderLayer build(TypeEnchantment enchantment, Identifier propertiesIdentifier) {
            class Texturing implements Runnable {
                private final float speed, rotation, r, g, b, a;
                private final WrappedMethodIntensity methodIntensity;

                Texturing(float speed, float rotation, float r, float g, float b, float a, WrappedMethodIntensity methodIntensity) {
                    this.speed = speed;
                    this.rotation = rotation;
                    this.r = r;
                    this.g = g;
                    this.b = b;
                    this.a = a;
                    this.methodIntensity = methodIntensity;
                }

                @Override
                public void run() {
                    float l = Util.getMeasuringTimeMs() * CITResewnDefaultsConfig.INSTANCE.type_enchantment_scroll_multiplier * speed;
                    float x = (l % 110000f) / 110000f;
                    float y = (l % 30000f) / 30000f;
                    Matrix4f matrix4f = Matrix4f.translate(-x, y, 0.0f);
                    matrix4f.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(rotation));
                    matrix4f.multiply(Matrix4f.scale(scale, scale, scale));
                    setTextureMatrix(matrix4f);

                    setShaderColor(r, g, b, a * methodIntensity.intensity);
                }
            }

            RenderLayer.MultiPhaseParameters.Builder layer = RenderLayer.MultiPhaseParameters.builder()
                    .texture(new RenderPhase.Texture(enchantment.texture, enchantment.blur, false))
                    .texturing(new RenderPhase.Texturing("citresewn_glint_texturing", new Texturing(enchantment.speed, enchantment.rotation, enchantment.r, enchantment.g, enchantment.b, enchantment.a, enchantment.methodIntensity), () -> {
                        RenderSystem.resetTextureMatrix();

                        setShaderColor(1f, 1f, 1f, 1f);
                    }))
                    .transparency(enchantment.blend);

            this.setup.accept(layer);

            return RenderLayer.of("citresewn:enchantment_" + this.name + ":" + propertiesIdentifier.toString(),
                    VertexFormats.POSITION_TEXTURE,
                    VertexFormat.DrawMode.QUADS,
                    256,
                    layer.build(false));
        }

        public VertexConsumer tryApply(VertexConsumer base, RenderLayer baseLayer, VertexConsumerProvider provider) {
            if (!CONTAINER.apply || CONTAINER.appliedContext == null || CONTAINER.appliedContext.size() == 0)
                return null;

            VertexConsumer[] layers = new VertexConsumer[Math.min(CONTAINER.appliedContext.size(), Integer.MAX_VALUE /*todo cap global property*/)];

            for (int i = 0; i < layers.length; i++)
                layers[i] = provider.getBuffer(CONTAINER.appliedContext.get(i).type.renderLayers.get(GlintRenderLayer.this));

            provider.getBuffer(baseLayer); // refresh base layer for armor consumer

            return base == null ? VertexConsumers.union(layers) : VertexConsumers.union(VertexConsumers.union(layers), base);
        }
    }

    private static class WrappedMethodIntensity {
        public float intensity = 1f;
    }

    public static class Blend extends RenderPhase.Transparency {
        private final int src, dst, srcAlpha, dstAlpha;

        private Blend(String name, int src, int dst, int srcAlpha, int dstAlpha) {
            super(name + "_glint_transparency", null, null);
            this.src = src;
            this.dst = dst;
            this.srcAlpha = srcAlpha;
            this.dstAlpha = dstAlpha;
        }

        private Blend(String name, int src, int dst) {
            this(name, src, dst, GL_ZERO, GL_ONE);
        }

        @Override
        public void startDrawing() {
            enableBlend();
            blendFuncSeparate(src, dst, srcAlpha, dstAlpha);
        }

        @Override
        public void endDrawing() {
            defaultBlendFunc();
            disableBlend();
        }

        public static Blend getBlend(String blendString) throws Exception {
            try { //check named blending function
                return Named.valueOf(blendString.toUpperCase(Locale.ENGLISH)).blend;
            } catch (IllegalArgumentException ignored) { // create custom blending function
                String[] split = blendString.split("\\p{Zs}+");
                int src, dst, srcAlpha, dstAlpha;
                if (split.length == 2) {
                    src = parseGLConstant(split[0]);
                    dst = parseGLConstant(split[1]);
                    srcAlpha = GL_ZERO;
                    dstAlpha = GL_ONE;
                } else if (split.length == 4) {
                    src = parseGLConstant(split[0]);
                    dst = parseGLConstant(split[1]);
                    srcAlpha = parseGLConstant(split[2]);
                    dstAlpha = parseGLConstant(split[3]);
                } else
                    throw new Exception();

                return new Blend("custom_" + src + "_" + dst + "_" + srcAlpha + "_" + dstAlpha, src, dst, srcAlpha, dstAlpha);
            }
        }

        private enum Named {
            REPLACE(new Blend("replace", 0, 0) {
                @Override
                public void startDrawing() {
                    disableBlend();
                }
            }),
            GLINT(new Blend("glint", GL_SRC_COLOR, GL_ONE)),
            ALPHA(new Blend("alpha", GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)),
            ADD(new Blend("add", GL_SRC_ALPHA, GL_ONE)),
            SUBTRACT(new Blend("subtract", GL_ONE_MINUS_DST_COLOR, GL_ZERO)),
            MULTIPLY(new Blend("multiply", GL_DST_COLOR, GL_ONE_MINUS_SRC_ALPHA)),
            DODGE(new Blend("dodge", GL_ONE, GL_ONE)),
            BURN(new Blend("burn", GL_ZERO, GL_ONE_MINUS_SRC_COLOR)),
            SCREEN(new Blend("screen", GL_ONE, GL_ONE_MINUS_SRC_COLOR)),
            OVERLAY(new Blend("overlay", GL_DST_COLOR, GL_SRC_COLOR));

            public final Blend blend;

            Named(Blend blend) {
                this.blend = blend;
            }
        }

        private static int parseGLConstant(String s) throws Exception {
            try {
                return GL11.class.getDeclaredField(s).getInt(null);
            } catch (NoSuchFieldException ignored) { }

            return s.startsWith("0x") ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s);
        }
    }

    public interface CITCacheEnchantment {
        CITCache.MultiList<TypeEnchantment> citresewn$getCacheTypeEnchantment();
    }
}
