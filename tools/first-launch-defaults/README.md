# Author resource selections on first launch

The original author options file also contains personal graphics/language/key
settings, so that file is not shipped as a root options.txt. Its selected resource
packs must still survive on a genuinely new profile. Only resourcePacks and
incompatibleResourcePacks are projected, byte-preserving their JSON values and
order, to config/yosbr/options.txt. Excalibur is present in the archive but is not
selected by the author and is not added to this selection.

Existing OpenLoader 21.1.5 was inspected first. Its config exposes pack discovery
locations and global pack loading; its repository constructs required packs. It
does not provide missing-options-file initialization of the exact selected list.
Using it to force these optional packs would override existing player choices.

The only added technical dependency is unmodified **YOSBR 0.1.2 by shedaniel**:
CurseForge project 374274, file 4581910, 17,373 bytes, SHA256
`db4c744fd71f5617639cb0fdff72378b08d2852004f4045c62090de1bf53afcb`.
Official project: https://www.curseforge.com/minecraft/mc-mods/yosbr
Source: https://github.com/shedaniel/your-options-shall-be-respected
License: LGPL-3.0-only. Its original JAR includes the license and is restored from
the pinned official CDN instead of being committed into this source repository.
The only declared dependency is Fabric Loader >=0.7.8+build.189; no Fabric API,
Minecraft classes, mixins or additional library updates are required.

The exact JAR was inspected: its LanguageAdapter constructor walks its default
config directory, and applyDefaultOptions returns immediately if the destination
already exists. It copies only absent files. No world, entity, inventory, terrain,
claim, networking or deferred-action code is present. This is a NEW technical
dependency; no BASE JAR is replaced and no terrain Safety change is required.

The launcher only delivers the mod and template. It never writes personal
options. Only the template path is pack-owned; root options.txt is never owned.
All existing options bytes, including resource choices and graphics settings,
take precedence. Newly initialized profiles receive the author's selected packs;
Minecraft supplies the ordinary defaults for every other unspecified option.

The overlay binds this projection to the original options SHA256. Updating the
author through update_upstream.py re-extracts only these two fields and updates
the binding. Extra defaults such as fov, render/simulation distance, language or
key assignments are rejected. The native mod pin survives the author update;
an author introducing the same mod ID requires explicit conflict review.

The actual unmodified JAR was run with the real Fabric Loader 0.19.5 API and a
synthetic path-only GameProvider. Missing options were initialized exactly;
existing personal bytes and modification time survived, including after changing
the defaults. This is the filesystem/API proof in native-proof.json, separate
from a real Minecraft client/bootstrap/resource-repository result.

```text
python tools/first-launch-defaults/verify_yosbr.py --shared PATH_TO_SHARED --yosbr mods/yosbr-0.1.2.jar --defaults config/yosbr/options.txt --output artifacts/yosbr-proof --jdk PATH_TO_JDK
```

Final client acceptance must start with absent options in an independent fixture,
inspect the actual selected resource packs/order and repeat with an existing
personal options file. Prior client evidence without this JAR is not a final
acceptance result for this composition.
