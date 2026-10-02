package com.nstut.endless.neoforge.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The config plugin removes only the native client renderer signature on dedicated servers. */
@Pseudo
@Mixin(targets = {
    "com.simibubi.create.content.equipment.symmetryWand.mirror.SymmetryMirror",
    "com.simibubi.create.content.equipment.symmetryWand.mirror.PlaneMirror",
    "com.simibubi.create.content.equipment.symmetryWand.mirror.CrossPlaneMirror"
}, remap = false)
public abstract class CreateSymmetryMirrorDistMixin {}
