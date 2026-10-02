package com.nstut.endless.testing;

import java.util.List;
import net.minecraft.SharedConstants;

/** Exact registered tests from pinned Create 6.0.8-289 / 6.0.11-312 sources. */
final class LiveCreateGameTestCatalog {
    private LiveCreateGameTestCatalog() {}
    static List<String> expected(String group) {
        return switch (SharedConstants.getCurrentVersion().getName() + "/" + group) {
            case "1.20.1/Contraptions" -> List.of(
                "TestContraptions.arrowDispenser", "TestContraptions.cropFarming", "TestContraptions.mountedItemExtract",
                "TestContraptions.mountedFluidDrain", "TestContraptions.ploughing", "TestContraptions.redstoneContacts",
                "TestContraptions.controls", "TestContraptions.elevator", "TestContraptions.rollerFilling",
                "TestContraptions.rollerPavingAndClearing", "TestContraptions.dispensersDontFight", "TestContraptions.dispensersRefill",
                "TestContraptions.vaultsProtectFuel");
            case "1.20.1/Fluids" -> List.of(
                "TestFluids.hosePulleyTransfer", "TestFluids.inWorldPumpingOut", "TestFluids.inWorldPumpingIn",
                "TestFluids.steamEngine", "TestFluids.threePipeCombine", "TestFluids.threePipeSplit",
                "TestFluids.largeWaterwheel", "TestFluids.smallWaterwheel", "TestFluids.waterwheelMaterials",
                "TestFluids.smartObserverPipes", "TestFluids.thresholdSwitch", "TestFluids.openPipes",
                "TestFluids.spouting");
            case "1.20.1/Items" -> List.of(
                "TestItems.andesiteTunnelSplit", "TestItems.armPurgatory", "TestItems.attributeFilters",
                "TestItems.beltCoaster", "TestItems.brassTunnelFiltering", "TestItems.brassTunnelPreferNearest",
                "TestItems.brassTunnelRoundRobin", "TestItems.brassTunnelSplit", "TestItems.brassTunnelSyncInput",
                "TestItems.smartObserverBeltAndFunnel", "TestItems.smartObserverChutes", "TestItems.smartObserverCounting",
                "TestItems.smartObserverFilteredStorage", "TestItems.smartObserverStorage", "TestItems.depotDisplay",
                "TestItems.thresholdSwitch", "TestItems.storages", "TestItems.vaultComparatorOutput",
                "TestItems.depotComparatorOutput", "TestItems.fanProcessing");
            case "1.20.1/Misc" -> List.of(
                "TestMisc.schematicannon", "TestMisc.shearing", "TestMisc.smartObserverBlocks",
                "TestMisc.thresholdSwitchPulley", "TestMisc.netheriteBacktank");
            case "1.20.1/Processing" -> List.of(
                "TestProcessing.brassMixing", "TestProcessing.brassMixing2", "TestProcessing.crushingWheelCrafting",
                "TestProcessing.precisionMechanismCrafting", "TestProcessing.sandWashing", "TestProcessing.stoneCobbleSandCrushing",
                "TestProcessing.trackCrafting", "TestProcessing.waterFillingBottle", "TestProcessing.wheatMilling");
            case "1.21.1/Contraptions" -> List.of(
                "TestContraptions.arrowDispenser", "TestContraptions.cropFarming", "TestContraptions.mountedItemExtract",
                "TestContraptions.mountedFluidDrain", "TestContraptions.ploughing", "TestContraptions.redstoneContacts",
                "TestContraptions.controls", "TestContraptions.elevator", "TestContraptions.rollerFilling",
                "TestContraptions.rollerPavingAndClearing", "TestContraptions.dispensersDontFight", "TestContraptions.dispensersRefill",
                "TestContraptions.vaultsProtectFuel");
            case "1.21.1/Fluids" -> List.of(
                "TestFluids.hosePulleyTransfer", "TestFluids.inWorldPumpingOut", "TestFluids.inWorldPumpingIn",
                "TestFluids.steamEngine", "TestFluids.threePipeCombine", "TestFluids.threePipeSplit",
                "TestFluids.largeWaterwheel", "TestFluids.smallWaterwheel", "TestFluids.waterwheelMaterials",
                "TestFluids.smartObserverPipes", "TestFluids.thresholdSwitch", "TestFluids.openPipes",
                "TestFluids.spouting");
            case "1.21.1/Items" -> List.of(
                "TestItems.andesiteTunnelSplit", "TestItems.armMultiOutput", "TestItems.armPurgatory",
                "TestItems.attributeFilters", "TestItems.beltCoaster", "TestItems.brassTunnelFiltering",
                "TestItems.brassTunnelPreferNearest", "TestItems.brassTunnelRoundRobin", "TestItems.brassTunnelSplit",
                "TestItems.brassTunnelSyncInput", "TestItems.smartObserverBeltAndFunnel", "TestItems.smartObserverChutes",
                "TestItems.smartObserverCounting", "TestItems.smartObserverFilteredStorage", "TestItems.smartObserverStorage",
                "TestItems.depotDisplay", "TestItems.thresholdSwitch", "TestItems.storages",
                "TestItems.vaultComparatorOutput", "TestItems.depotComparatorOutput", "TestItems.fanProcessing");
            case "1.21.1/Misc" -> List.of(
                "TestMisc.schematicannon", "TestMisc.shearing", "TestMisc.smartObserverBlocks",
                "TestMisc.thresholdSwitchPulley", "TestMisc.netheriteBacktank");
            case "1.21.1/Processing" -> List.of(
                "TestProcessing.brassMixing", "TestProcessing.brassMixing2", "TestProcessing.potionBrewing",
                "TestProcessing.spoutCrafting", "TestProcessing.crushingWheelCrafting", "TestProcessing.precisionMechanismCrafting",
                "TestProcessing.sandWashing", "TestProcessing.stoneCobbleSandCrushing", "TestProcessing.trackCrafting",
                "TestProcessing.waterFillingBottle", "TestProcessing.wheatMilling");
            case "1.21.1/Regressions" -> List.of(
                "TestRegressions.issue9615_efficientDeployers");
            default -> throw new IllegalStateException("Unsupported pinned Create group " + group);
        };
    }
}
