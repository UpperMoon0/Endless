# Forge 1.20.1 / Embeddium global renderer fix

An assembled Create elevator can move normally while its rope disappears when
the pulley origin leaves Endless's bounded terrain render window. Embeddium
normally discovers global block entity renderers through compiled render
sections. Removing the origin section therefore removes the entire long rope.

Create 6.0.8's ElevatorPulleyRenderer also inherits the normal 64-block origin
distance limit, unlike its ordinary rope pulley renderer. Measure that distance
from the elevator rope's render bounds, retaining the same 64-block cutoff for
the visible geometry. This prevents the belt and magnet disappearing next to
the cabin while its controller is hundreds of blocks above it.

Retain native off-screen renderers from already-loaded columns when their
bounding boxes are visible. Include origins inside the window whose render
sections have not been compiled. Skip block entities already in Embeddium's
compiled global list by identity, preventing duplicate draws. No terrain sections are
added, no chunks are loaded, and the logical height is never scanned.

This change targets Forge 1.20.1 with Embeddium 0.3.31, including the Oculus
fallback renderer used for the trailer. It does not change elevator assembly,
movement, rope length, server data, or support for other loader/version pairs.

Build: Java 21 runs Gradle; emitted Minecraft code targets Java 17.
Validation receipts and before/after native images are in the trailer project's
evidence/revision13-* files. Common suite: 45 passing tests, including five
distance regression tests. The final clean client artifact also passed a native
passenger ride from walking Y=304 across Y=320 to Y=336 and Y=512. Native
framebuffer views confirm the belt and magnet meet the cabin roof at the lower
and upper stops. Trailer exports are checked separately before delivery.
