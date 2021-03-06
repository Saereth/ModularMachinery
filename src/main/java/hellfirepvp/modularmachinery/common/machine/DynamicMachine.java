/*******************************************************************************
 * HellFirePvP / Modular Machinery 2017
 *
 * This project is licensed under GNU GENERAL PUBLIC LICENSE Version 3.
 * The source code is available on github: https://github.com/HellFirePvP/ModularMachinery
 * For further details, see the License file there.
 ******************************************************************************/

package hellfirepvp.modularmachinery.common.machine;

import com.google.common.collect.Lists;
import com.google.gson.*;
import hellfirepvp.modularmachinery.ModularMachinery;
import hellfirepvp.modularmachinery.common.crafting.MachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.RecipeRegistry;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.util.BlockArray;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StringUtils;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistryEntry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * This class is part of the Modular Machinery Mod
 * The complete source code for this mod can be found on github.
 * Class: DynamicMachine
 * Created by HellFirePvP
 * Date: 27.06.2017 / 13:57
 */
public class DynamicMachine {

    @Nonnull
    private final ResourceLocation registryName;
    private String localizedName = null;
    private BlockArray pattern = new BlockArray();

    private boolean requiresBlueprint = false;

    public DynamicMachine(@Nonnull ResourceLocation registryName) {
        this.registryName = registryName;
    }

    public void setRequiresBlueprint() {
        this.requiresBlueprint = true;
    }

    public boolean requiresBlueprint() {
        return requiresBlueprint;
    }

    public BlockArray getPattern() {
        return pattern;
    }

    public void setLocalizedName(String localizedName) {
        this.localizedName = localizedName;
    }

    public String getLocalizedName() {
        return localizedName;
    }

    @Nonnull
    public ResourceLocation getRegistryName() {
        return registryName;
    }

    @Nonnull
    public List<MachineRecipe> getAvailableRecipes() {
        return RecipeRegistry.getRegistry().getRecipesFor(this);
    }

    public RecipeCraftingContext createContext(MachineRecipe recipe, List<MachineComponent> components) {
        if(!recipe.getOwningMachineIdentifier().equals(getRegistryName())) {
            throw new IllegalArgumentException("Tried to create context for a recipe that doesn't belong to the referenced machine!");
        }
        RecipeCraftingContext context = new RecipeCraftingContext(recipe);
        for (MachineComponent component : components) {
            context.addComponent(component);
        }
        return context;
    }

    public static class MachineDeserializer implements JsonDeserializer<DynamicMachine> {

        @Override
        public DynamicMachine deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject root = json.getAsJsonObject();
            String registryName = JsonUtils.getString(root, "registryname", "");
            if(registryName.isEmpty()) {
                throw new JsonParseException("Invalid/Missing 'registryname' !");
            }
            String localized = JsonUtils.getString(root, "localizedname", "");
            if(localized.isEmpty()) {
                throw new JsonParseException("Invalid/Missing 'localizedname' !");
            }
            JsonArray parts = JsonUtils.getJsonArray(root, "parts", new JsonArray());
            if(parts.size() == 0) {
                throw new JsonParseException("Empty/Missing 'parts'!");
            }
            DynamicMachine machine = new DynamicMachine(new ResourceLocation(ModularMachinery.MODID, registryName));
            machine.setLocalizedName(localized);
            if(root.has("requires-blueprint")) {
                JsonElement elementBlueprint = root.get("requires-blueprint");
                if(!elementBlueprint.isJsonPrimitive() || !elementBlueprint.getAsJsonPrimitive().isBoolean()) {
                    throw new JsonParseException("'requires-blueprint' has to be either 'true' or 'false'!");
                }
                boolean requiresBlueprint = elementBlueprint.getAsJsonPrimitive().getAsBoolean();
                if(requiresBlueprint) {
                    machine.setRequiresBlueprint();
                }
            }

            for (int i = 0; i < parts.size(); i++) {
                JsonElement element = parts.get(i);
                if(!element.isJsonObject()) {
                    throw new JsonParseException("A part of 'parts' is not a compound object!");
                }
                JsonObject part = element.getAsJsonObject();
                if(!part.has("elements")) {
                    throw new JsonParseException("Part contained no element!");
                }
                JsonElement partElement = part.get("elements");
                if(partElement.isJsonPrimitive() && partElement.getAsJsonPrimitive().isString()) {
                    String strDesc = partElement.getAsString();
                    BlockArray.BlockInformation descr = MachineLoader.variableContext.get(strDesc);
                    if(descr == null) {
                        descr = new BlockArray.BlockInformation(Lists.newArrayList(BlockArray.BlockInformation.getDescriptor(partElement.getAsJsonPrimitive())));
                    }
                    addDescriptorWithPattern(machine.getPattern(), descr, part);
                } else if(partElement.isJsonArray()) {
                    JsonArray elementArray = partElement.getAsJsonArray();
                    List<BlockArray.IBlockStateDescriptor> descriptors = Lists.newArrayList();
                    for (int xx = 0; xx < elementArray.size(); xx++) {
                        JsonElement p = elementArray.get(xx);
                        if(!p.isJsonPrimitive() || !p.getAsJsonPrimitive().isString()) {
                            throw new JsonParseException("Part elements of 'elements' have to be blockstate descriptions!");
                        }
                        descriptors.add(BlockArray.BlockInformation.getDescriptor(p.getAsJsonPrimitive()));
                    }
                    if(descriptors.isEmpty()) {
                        throw new JsonParseException("'elements' array didn't contain any blockstate descriptors!");
                    }
                    addDescriptorWithPattern(machine.getPattern(), new BlockArray.BlockInformation(descriptors), part);
                } else {
                    throw new JsonParseException("'elements' has to either be a blockstate description, variable or array of blockstate descriptions!");
                }
            }
            return machine;
        }

        private void addDescriptorWithPattern(BlockArray pattern, BlockArray.BlockInformation information, JsonObject part) throws JsonParseException {
            List<Integer> avX = new ArrayList<>();
            List<Integer> avY = new ArrayList<>();
            List<Integer> avZ = new ArrayList<>();
            addCoordinates("x", part, avX);
            addCoordinates("y", part, avY);
            addCoordinates("z", part, avZ);

            for (BlockPos permutation : buildPermutations(avX, avY, avZ)) {
                if(permutation.getX() == 0 && permutation.getY() == 0 && permutation.getZ() == 0) {
                    continue; //We're not going to overwrite the controller.
                }
                pattern.addBlock(permutation, information);
            }
        }

        private List<BlockPos> buildPermutations(List<Integer> avX, List<Integer> avY, List<Integer> avZ) {
            List<BlockPos> out = new ArrayList<>(avX.size() * avY.size() * avZ.size());
            for (int x : avX) {
                for (int y : avY) {
                    for (int z : avZ) {
                        out.add(new BlockPos(x, y, z));
                    }
                }
            }
            return out;
        }

        private void addCoordinates(String key, JsonObject part, List<Integer> out) throws JsonParseException {
            if(!part.has(key)) {
                out.add(0);
                return;
            }
            JsonElement coordinateElement = part.get(key);
            if(coordinateElement.isJsonPrimitive() && coordinateElement.getAsJsonPrimitive().isNumber()) {
                out.add(coordinateElement.getAsInt());
            } else if(coordinateElement.isJsonArray() && coordinateElement.getAsJsonArray().size() > 0) {
                for (JsonElement element : coordinateElement.getAsJsonArray()) {
                    if(element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                        out.add(element.getAsInt());
                    } else {
                        throw new JsonParseException("Expected only numbers in JsonArray " + coordinateElement.toString() + " but found " + element.toString());
                    }
                }
            }
        }

    }

}
