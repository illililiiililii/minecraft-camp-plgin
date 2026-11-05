# Verification — CampfireCoop 1.7.0

- 2026-11-05: Maven verify succeeded: 59 tests, 0 failures, 0 errors, 0 skipped.
- Feature tests cover cooperative progress/rewards, inventory protection, tool charges, death chest persistence, guide retention, queued supply delivery/pickup/recovery, bounty limits, combo rules, fishing cooldown, and child-class-loader isolation.
- MockBukkit lacks Adventure book pages and item ownership. Test-only shims implement those missing metadata APIs. Player disk save is replaced in test players. These shims are excluded from distributed JARs.
- Real Paper 1.21.11 build 132 started the stable loader and child runtime using a copy of the existing plugin data. Runtime reported chapter 4 after reading the existing state.
- An isolated server on 127.0.0.1:25566 changed from runtime 1.7.0 to 1.7.0-hotcheck while its JVM process and listener stayed alive.
- An intentionally failing test module was rejected; the previous working runtime and YAML snapshot were restored in the same server process.
- The user-designated server was stopped with Bukkit's normal stop command. Logs confirmed all dimensions saved. A verified local backup was created before installing the stable loader and runtime.
- The designated server started runtime 1.7.0, responded to the Minecraft status protocol on port 25565, and accepted a player connection.

Full client-side gameplay/rendering and arbitrary third-party plugin interactions remain outside this verification. Core-server/Java/loader changes still require a normal restart; runtime module updates keep connections alive and cancel transient expeditions/warps.
