# Room entities / DAOs are generated and kept by Room's own rules.
-keepattributes *Annotation*

# Glance runs widget action callbacks by class name
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
