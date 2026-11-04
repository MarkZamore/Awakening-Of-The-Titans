# Native Fabric network exception logging

This is a scoped copy of the launcher's `tools/PackData/Logging` standalone
policy. It is pack source data, not a launcher callback. The source entrypoint is
`tools/network_logging_policy.py`; ordinary preparation and publication verify
the pinned output and never regenerate XML at game launch.

Fabric 0.19.5 loads Minecraft 1.21.1 in the intermediary namespace. The actual
initialized Connection logger is **net.minecraft.class_2535**, not the MojMap
name net.minecraft.network.Connection. The native `exceptionCaught` method has
four Throwable-bearing DEBUG messages: Failed to sent packet, Double fault,
Skipping packet due to errors, and Timeout. The original official class is vt;
exact class/JAR hashes and proof results are in `native-proof.json`.

The template starts from Mojang's official `client-1.12.xml` selected by the
1.21.1 version descriptor: 888 bytes, SHA1
`bd65e7d2e3c237be76cfbef4c2405033d7f91521`, SHA256
`03e30ee5bd5c1fc723d1049e2e4e8b3ad4c4992514f3206fc4b54637488b83e4`.
The original file is retained here for reproducibility. Native LegacyXML console
layout and NETWORK_PACKETS Root marker filtering are preserved. The file pattern
retains its native fields with an explicit Throwable formatter; bounded 32 MB
rollovers, five files, seven-day expiry and the narrowly filtered Connection
logger are added. Console stays at INFO. No Forge/NeoForge-only appenders,
formatters or diagnostic namespaces are introduced.

The delivery template is SHA256
`c794d7578091045f7f46aa9dbc52c8859b287d192583031f8fc59dcd2f751015`.
The only JVM selector is
`-Dlog4j.configurationFile=config/log4j2.xml`; only config/log4j2.xml is added
to the existing exact ownership claims. Original pack JARs and gameplay are
unchanged. New diagnostic stack details are an introduced observability change,
not inherited baseline log content or a network repair.

`tools/overlay.json` requires the profile, pins its receipt and reapplies its
config/selector/ownership on each author import. Missing XML, changed filters,
duplicate selectors, missing ownership or a stale receipt stop verification
before manifest generation. A future author XML at the same path conflicts
instead of being discarded: review it with the standalone prepare tool,
preserve other settings, then update the exact overlay pins. New Minecraft or
loader namespaces require requalification.

Reproduce the actual native-class logger resolution and full-template filter
test using a prepared 1.21.1 Shared runtime and the helper's audited intermediary
JAR:

```text
python tools/logging/verify_native_logging.py --shared PATH_TO_SHARED --intermediary-jar PATH_TO_INTERMEDIARY_JAR --output artifacts/logging-proof --jdk PATH_TO_JDK
python tools/network_logging_policy.py verify --pack .
python -B -m unittest discover -s tools -p test_network_logging_policy.py
```

The native test uses real Log4j 2.22.1 and Minecraft's LegacyXML plugin. All four
caught stacks reach latest.log; normal INFO/WARN/ERROR survive. Packet/other
DEBUG are rejected, and caught DEBUG stacks do not reach the console. This is
a native-input/logging-library proof, separate from full-client and Steam-LAN
acceptance recorded in the pack TESTING-STATUS.
