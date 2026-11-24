# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, and readable stack traces matter in a sideloaded app where a crash arrives as a
# photo of the screen.
-dontobfuscate
