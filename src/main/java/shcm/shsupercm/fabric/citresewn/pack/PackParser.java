package shcm.shsupercm.fabric.citresewn.pack;

import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import shcm.shsupercm.fabric.citresewn.CITResewn;
import shcm.shsupercm.fabric.citresewn.cit.builtin.conditions.core.FallbackCondition;
import shcm.shsupercm.fabric.citresewn.cit.builtin.conditions.core.WeightCondition;
import shcm.shsupercm.fabric.citresewn.ex.CITParsingException;
import shcm.shsupercm.fabric.citresewn.cit.CIT;
import shcm.shsupercm.fabric.citresewn.cit.CITCondition;
import shcm.shsupercm.fabric.citresewn.cit.CITRegistry;
import shcm.shsupercm.fabric.citresewn.cit.CITType;
import shcm.shsupercm.fabric.citresewn.pack.format.PropertyGroup;
import shcm.shsupercm.fabric.citresewn.pack.format.PropertyKey;
import shcm.shsupercm.fabric.citresewn.pack.format.PropertyValue;

import java.io.FileNotFoundException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility parsing methods for packs.
 */
public final class PackParser { private PackParser() {}
    /**
     * Possible CIT roots in resourcepacks ordered in increasing order of priority.
     */
    private static final String[] ROOTS = new String[] { "mcpatcher", "optifine", "citresewn" };

    /**
     * Loads a merged global property group from loaded packs making sure to respect order.
     *
     * @see GlobalProperties#callHandlers()
     * @param resourceManager the manager that contains the packs
     * @param globalProperties global property group to parse into
     * @return globalProperties
     */
    public static GlobalProperties loadGlobalProperties(ResourceManager resourceManager, GlobalProperties globalProperties) {
        for (ResourcePack pack : resourceManager.streamResourcePacks().collect(Collectors.toList()))
            for (String namespace : pack.getNamespaces(ResourceType.CLIENT_RESOURCES))
                for (String root : ROOTS) {
                    Identifier identifier = new Identifier(namespace, root + "/cit.properties");
                    try {
                        if (pack.contains(ResourceType.CLIENT_RESOURCES, identifier))
                            globalProperties.load(pack.getName(), identifier, pack.open(ResourceType.CLIENT_RESOURCES, identifier));
                    } catch (FileNotFoundException ignored) {
                    } catch (Exception e) {
                        CITResewn.logErrorLoading("Errored while loading global properties: " + identifier + " from " + pack.getName());
                        e.printStackTrace();
                    }
                }
        return globalProperties;
    }

    /**
     * Attempts parsing all CITs out of all loaded packs.
     * @param resourceManager the manager that contains the packs
     * @return unordered list of successfully parsed CITs
     */
    public static List<CIT<?>> parseCITs(ResourceManager resourceManager) {
        List<CIT<?>> cits = new ArrayList<>();

        for (String root : ROOTS)
            for (Identifier identifier : resourceManager.findResources(root + "/cit", s -> s.endsWith(".properties"))) {
                String packName = null;
                try (Resource resource = resourceManager.getResource(identifier)) {
                    cits.add(parseCIT(PropertyGroup.tryParseGroup(packName = resource.getResourcePackName(), identifier, resource.getInputStream()), resourceManager));
                } catch (CITParsingException e) {
                    CITResewn.logErrorLoading(e.getMessage());
                } catch (Exception e) {
                    CITResewn.logErrorLoading("Errored while loading cit: " + identifier + (packName == null ? "" : " from " + packName));
                    e.printStackTrace();
                }
            }

        return cits;
    }

    /**
     * Attempts parsing a CIT from a property group.
     * @param properties property group representation of the CIT
     * @param resourceManager the manager that contains the the property group, used to resolve relative assets
     * @return the successfully parsed CIT
     * @throws CITParsingException if the CIT failed parsing for any reason
     */
    public static CIT<?> parseCIT(PropertyGroup properties, ResourceManager resourceManager) throws CITParsingException {
        CITType citType = CITRegistry.parseType(properties);

        List<CITCondition> conditions = new ArrayList<>();

        Set<PropertyKey> ignoredProperties = citType.typeProperties();

        for (Map.Entry<PropertyKey, Set<PropertyValue>> entry : properties.properties.entrySet()) {
            if (entry.getKey().path().equals("type") && entry.getKey().namespace().equals("citresewn"))
                continue;
            if (ignoredProperties.contains(entry.getKey()))
                continue;

            for (PropertyValue value : entry.getValue())
                conditions.add(CITRegistry.parseCondition(entry.getKey(), value, properties));
        }

        for (CITCondition condition : new ArrayList<>(conditions))
            if (condition != null)
                for (Class<? extends CITCondition> siblingConditionType : condition.siblingConditions())
                    conditions.replaceAll(
                            siblingCondition -> siblingCondition != null && siblingConditionType == siblingCondition.getClass() ?
                                    condition.modifySibling(siblingCondition) :
                                    siblingCondition);

        WeightCondition weight = new WeightCondition();
        FallbackCondition fallback = new FallbackCondition();

        conditions.removeIf(condition -> {
            if (condition instanceof WeightCondition weightCondition) {
                weight.setWeight(weightCondition.getWeight());
                return true;
            } else if (condition instanceof FallbackCondition fallbackCondition) {
                fallback.setFallback(fallbackCondition.getFallback());
                return true;
            }

            return condition == null;
        });

        citType.load(conditions, properties, resourceManager);

        return new CIT<>(properties.identifier, properties.packName, citType, conditions.toArray(new CITCondition[0]), weight.getWeight(), fallback.getFallback());
    }
}
