import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable-world QA only. Calls native game/mod APIs; no transformers or production patches. */
public final class AotGameplayProbeV3 {
    private static final AtomicBoolean busy=new AtomicBoolean();
    private static volatile String status="not_started",clientAction="";
    private static volatile boolean terminal;
    private static int phase,scenario,waits;
    private static UUID playerId;
    private static String playerName;
    private static ClassLoader loader;
    private static Path report;
    private static Set<UUID> beforeGraves;
    private static Object grave,world;
    private static Map<String,Integer> expected;
    private static final Set<String> nestedDropKeys=new HashSet<>();
    private static boolean nativeShiftObserved;
    private static long startTick,startNanos;
    private static int forestX,forestZ,fireX,fireY,fireZ;
    private static Map<String,String> burnBefore,burnImmediate;
    private static final List<Map<String,Object>> results=new ArrayList<>();
    private AotGameplayProbeV3() { }

    /** Invoke on the client thread every ~3s, only after the QA coordinator enables gameplay. */
    public static String tick(Object client,Object server,Object clientPlayer,String reportPath) {
        if(terminal)return status;
        try {
            if(server==null||clientPlayer==null)return "waiting_for_integrated_server";
            if(report==null)guard(reportPath);
            String action=clientAction;clientAction="";
            if(action.equals("respawn"))call(clientPlayer,"method_7331");
            if(action.equals("shift"))staticCall("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking","send",construct("daot.network.TitanShiftPayload"));
            if(!busy.compareAndSet(false,true))return status;
            ((Executor)server).execute(()->{
                try { step(server); }
                catch(Throwable failure){
                    Throwable cause=failure instanceof InvocationTargetException inv?inv.getTargetException():failure;
                    try{event("probe_failure",Map.of("phase",phase,"exception",cause.toString(),"pass",false));}catch(Exception ignored){}
                    status="FAILED phase="+phase+" "+cause;terminal=true;
                } finally {busy.set(false);}
            });
            return status;
        }catch(Throwable failure){status="FAILED client "+failure;terminal=true;return status;}
    }

    private static void guard(String reportPath)throws Exception {
        Path root=Path.of(required("root")).toRealPath();
        if(!Files.isRegularFile(root.resolve(".aot-runtime-qa.json")))throw new SecurityException("Unmarked fixture");
        boolean artifacts=false;for(Path p:root)if(p.toString().equalsIgnoreCase("artifacts"))artifacts=true;
        if(!artifacts)throw new SecurityException("Fixture outside artifacts");
        Path target=Path.of(reportPath).toAbsolutePath().normalize();
        if(!target.startsWith(root))throw new SecurityException("Report outside disposable fixture");
        Files.createDirectories(target.getParent());report=target;
    }

    private static void step(Object server)throws Exception {
        loader=server.getClass().getClassLoader();
        List<?> players=(List<?>)call(call(server,"method_3760"),"method_14571");
        if(players.size()!=1)throw new SecurityException("Gameplay probe requires exactly one fixture player");
        Object player=players.get(0);
        if(playerId==null){
            playerId=(UUID)call(player,"method_5667");
            playerName=String.valueOf(call(call(player,"method_7334"),"getName"));
            if(!playerName.matches("[A-Za-z0-9_]{1,16}"))throw new SecurityException("Unsafe fixture player name");
        }
        if(!playerId.equals(call(player,"method_5667")))throw new SecurityException("Fixture player changed");
        switch(phase){
            // Shifter scenario comes first in a fresh fixture, before any native
            // defeat/death recovery timer can legitimately prevent transformation.
            case 0 -> {scenario=1;prepareDeath(server,player,false);phase=4;}
            case 1 -> observeDeath(server,player);
            case 2 -> claim(server,player);
            case 3 -> verifyRestore(server,player);
            case 4 -> beginShift(server,player);
            case 5 -> observeShift(server,player);
            case 10 -> beginForest(server,player);
            case 11 -> igniteForest(server,player);
            case 12 -> observeBurn(server,player);
            default -> throw new IllegalStateException("Unknown phase "+phase);
        }
    }

    private static void prepareDeath(Object server,Object player,boolean killNow)throws Exception {
        command(server,"gamemode survival "+playerName);
        Object inv=call(player,"method_31548");call(inv,"method_5448");
        Object gear=stack(field("daot.DannysAot","ODM_GEAR"),1);
        Object harness=stack(field("daot.DannysAot","ODM_HARNESS"),1);
        Object main=stack(field("daot.DannysAot","BLADE"),1),off=stack(field("daot.DannysAot","BLADE"),1);
        Object gas=stack(field("daot.DannysAot","GAS_CANISTER"),1);
        staticCall("daot.DannysAot","setGasOnGear",gear,321);
        staticCall("daot.OdmHarness","setGear",harness,gear);
        staticCall("daot.GasCanisterItem","setGas",gas,73);
        staticCall("daot.BladeItem","reloadBlade",main);staticCall("daot.BladeItem","reloadBlade",off);
        call(inv,"method_5447",37,harness);call(inv,"method_5447",0,main);call(inv,"method_5447",40,off);
        call(inv,"method_5447",1,gas);call(inv,"method_5447",2,stack(field("daot.DannysAot","BLADE_COMPONENT"),16));
        field(inv.getClass(),"field_7545").setInt(inv,0);
        assertNativeWornLoadout(server,player,"before_death");
        nestedDropKeys.clear();nestedDropKeys.addAll(stacks(server,List.of(gear)).keySet());
        world=call(player,"method_37908");expected=inventory(server,player);beforeGraves=graveIds();waits=0;
        event("death_loadout",Map.of("scenario",scenario,"inventory",expected,"gearGas",staticCall("daot.DannysAot","getGasFromGear",gear),"canisterGas",staticCall("daot.GasCanisterItem","getGas",gas),"nativeReloadedPairedGrips",true,"fixtureInventoryCleared",true,"nativeHarnessWithNestedGear",true));
        if(killNow)command(server,"kill "+playerName);
        status=killNow?"waiting_for_yigd_grave":"preparing_native_shifter_before_first_death";
    }

    private static void observeDeath(Object server,Object player)throws Exception {
        Set<UUID> after=graveIds();after.removeAll(beforeGraves);
        if(after.isEmpty()){
            if(++waits>12)throw new AssertionError("No YIGD grave after native player death");return;
        }
        if(after.size()!=1)throw new AssertionError("Expected one new grave, found "+after.size());
        grave=graveById(after.iterator().next());
        Map<String,Integer> actual=graveInventory(server,grave);
        if(!actual.equals(expected))throw new AssertionError("Grave differs from equipped inventory: expected="+expected+" actual="+actual);
        if(!inventory(server,player).isEmpty())throw new AssertionError("Dead fixture player still holds inventory");
        Object pos=call(grave,"getPos");world=call(grave,"getWorld");
        Object be=call(world,"method_8321",pos);
        if(be==null||!be.getClass().getName().equals("com.b1n_ry.yigd.block.entity.GraveBlockEntity"))throw new AssertionError("No real YIGD block entity at grave");
        int drops=matchingDrops(server,world,pos);
        if(drops!=0)throw new AssertionError("Expected inventory also appears in ground drops: "+drops);
        event("grave_stored_inventory",Map.of("scenario",scenario,"newGraves",1,"inventory",actual,"graveStatus",call(grave,"getStatus").toString(),"matchingGroundItems",drops,"pass",true));
        clientAction="respawn";phase=2;waits=0;status="waiting_for_native_respawn";
    }

    private static void claim(Object server,Object player)throws Exception {
        if(!(Boolean)call(player,"method_5805")){if(++waits>12)throw new AssertionError("Native respawn timed out");return;}
        Object pos=call(grave,"getPos");world=call(grave,"getWorld");
        int x=coordinate(pos,"method_10263"),y=coordinate(pos,"method_10264"),z=coordinate(pos,"method_10260");
        String dimension=String.valueOf(call(call(world,"method_27983"),"method_29177"));
        command(server,"execute in "+dimension+" run tp "+playerName+" "+(x+.5)+" "+(y+1.0)+" "+(z+.5));
        Object state=call(world,"method_8320",pos),block=call(state,"method_26204");
        Object vec=construct("net.minecraft.class_243",x+.5,y+.5,z+.5);
        Object up=enumValue("net.minecraft.class_2350","UP");
        Object hit=construct("net.minecraft.class_3965",vec,up,pos,false);
        Object result=call(block,"method_55766",state,world,pos,player,hit);
        event("native_grave_interaction",Map.of("scenario",scenario,"result",String.valueOf(result),"route","GraveBlock.onUseWithoutItem"));
        phase=3;waits=0;status="verifying_yigd_restore";
    }

    private static void verifyRestore(Object server,Object player)throws Exception {
        Map<String,Integer> actual=inventory(server,player);
        assertNativeWornLoadout(server,player,"after_restore");
        if(!actual.equals(expected))throw new AssertionError("Restored inventory differs: expected="+expected+" actual="+actual);
        // YIGD deliberately retains historical backup contents for admin recovery.
        // Availability is governed by CLAIMED status and the live block entity.
        if(!call(grave,"getStatus").toString().equals("CLAIMED"))throw new AssertionError("Grave remains claimable after restore");
        Object be=call(world,"method_8321",call(grave,"getPos"));
        if(be!=null&&be.getClass().getName().equals("com.b1n_ry.yigd.block.entity.GraveBlockEntity")&&(Boolean)call(be,"isUnclaimed"))throw new AssertionError("Live grave still claims to be unclaimed");
        int drops=matchingDrops(server,world,call(grave,"getPos"));
        if(drops!=0)throw new AssertionError("Duplicate matching ground items after restore: "+drops);
        event("grave_restore",Map.of("scenario",scenario,"inventory",actual,"matchingGroundItems",drops,"graveStatus",call(grave,"getStatus").toString(),"pass",true));
        if(scenario==1){scenario=0;prepareDeath(server,player,true);phase=1;}else if(Files.isRegularFile(report.getParent().resolve("graves-only.flag"))){
            event("graves_probe_final",Map.of("status",nativeShiftObserved?"PASS":"PARTIAL_NATIVE_SHIFT_NOT_OBSERVED","ordinaryRestored",true,"transformedNativeAttackTitanRestored",nativeShiftObserved,"nativeWornHarnessNestedGear",true,"burntRepeated",false));
            terminal=true;status=nativeShiftObserved?"PASS native nested-harness grave scenarios":"PARTIAL native shift not observed";
        }else{phase=10;status="grave_scenarios_complete";}
    }

    private static void beginShift(Object server,Object player)throws Exception {
        // Commands use Danny's own authority/provenance path; tags are never forged.
        // The native players-only EntityArgument rejects raw UUID literals as
        // potentially non-player selectors. Use the validated real profile name.
        command(server,"daot bloodline set "+playerName+" eldian");
        command(server,"daot shifter set "+playerName+" attack");
        Object power=enumValue("daot.TitanPowerType","ATTACK");
        Object bloodlines=staticCall("daot.BloodlineData","get",server);
        boolean authority=(Boolean)staticCall("daot.PowerAuthority","hasShifter",player,power);
        event("native_shifter_assignment",Map.of("commandsIssued",true,"authorityBypassed",false,"nativePowerAuthorityHasShifter",authority,"nativeBloodline",String.valueOf(call(bloodlines,"getBloodline",playerId)),"activeEffects",String.valueOf(call(player,"method_6026")),"shiftRequestRoute","same TitanShiftPayload sent by DannysAotClient key handler"));
        if(!authority)throw new AssertionError("Native commands did not grant author-authorized Attack Titan power");
        clientAction="shift";waits=0;phase=5;status="waiting_for_native_titan_shift";
    }

    private static void observeShift(Object server,Object player)throws Exception {
        Object vehicle=call(player,"method_5854");
        if(vehicle!=null&&vehicle.getClass().getName().equals("daot.AttackTitanEntity")&&playerId.equals(call(vehicle,"getShifterUUID"))){
            nativeShiftObserved=true;assertNativeWornLoadout(server,player,"before_transformed_death");
            expected=inventory(server,player);
            scenario=1;beforeGraves=graveIds();waits=0;
            event("native_titan_shift_observed",Map.of("vehicle",vehicle.getClass().getName(),"inventoryBeforeShiftDeath",inventory(server,player),"originalLoadout",expected));
            command(server,"kill "+playerName);phase=1;status="waiting_for_transformed_yigd_grave";
        }else if(++waits>20){
            event("transformed_death_variant",Map.of("pass",false,"status","NOT_TESTED_NATIVE_SHIFT_NOT_OBSERVED_WITHIN_60_SECONDS","vehicle",String.valueOf(vehicle)));
            scenario=0;prepareDeath(server,player,true);phase=1;status="native_shift_timeout_normal_death_next";
        }
    }

    private static void beginForest(Object server,Object player)throws Exception {
        command(server,"gamemode creative "+playerName);
        world=call(player,"method_37908");
        int originX=(int)Math.floor((Double)call(player,"method_23317")),originZ=(int)Math.floor((Double)call(player,"method_23321"));
        Object chunkSource=call(world,"method_14178");
        Object generator=call(chunkSource,"method_12129"),biomeSource=call(generator,"method_12098");
        Object sampler=call(call(chunkSource,"method_41248"),"method_42371");
        Method query=nativeMethod(biomeSource.getClass(),"method_38109",4);
        Method key=Class.forName("net.minecraft.class_6880",false,loader).getMethod("method_40230");
        Method location=Class.forName("net.minecraft.class_5321",false,loader).getMethod("method_29177");
        boolean found=false;int queries=0;
        // Query the actual initialized source/sampler, without generating chunks.
        // The author's city exclusion region can extend far beyond8192 blocks.
        search:for(int radius=0;radius<=131072;radius+=1024)for(int offset=-radius;offset<=radius;offset+=1024)for(int[] edge:new int[][]{{offset,-radius},{offset,radius},{-radius,offset},{radius,offset}}){
            int x=originX+edge[0],z=originZ+edge[1];queries++;
            if(nativeBiomeId(query,biomeSource,sampler,key,location,x,z).equals("dannys-aot:giant_forest")){
                boolean interior=true;
                for(int[] margin:new int[][]{{64,0},{-64,0},{0,64},{0,-64}}){queries++;if(!nativeBiomeId(query,biomeSource,sampler,key,location,x+margin[0],z+margin[1]).equals("dannys-aot:giant_forest")){interior=false;break;}}
                if(interior){forestX=x;forestZ=z;found=true;break search;}
            }
        }
        if(!found)throw new AssertionError("Actual native BiomeSource did not find giant forest within bounded131072 search; queries="+queries);
        command(server,"execute in dannys-aot:paradis run tp "+playerName+" "+forestX+" 200 "+forestZ);
        event("native_giant_forest_candidate",Map.of("x",forestX,"z",forestZ,"generator",generator.getClass().getName(),"biomeSource",biomeSource.getClass().getName(),"queriesWithoutChunkGeneration",queries,"manuallyGeneratedTerrain",false));
        phase=11;waits=0;status="waiting_for_giant_forest_chunks";
    }

    private static void igniteForest(Object server,Object player)throws Exception {
        world=call(player,"method_37908");
        String biome=String.valueOf(call(((Optional<?>)call(call(world,"method_23753",position(forestX,100,forestZ)),"method_40230")).orElseThrow(),"method_29177"));
        if(!biome.equals("dannys-aot:giant_forest"))throw new AssertionError("Candidate actual biome is "+biome);
        boolean found=false;
        search:for(int radius=0;radius<=24;radius+=2)for(int dx=-radius;dx<=radius;dx+=2)for(int dz:new int[]{-radius,radius})for(int y=48;y<=200;y+=4){
            int x=forestX+dx,z=forestZ+dz;String id=blockId(call(world,"method_8320",position(x,y,z)));
            if(id.endsWith("_log")||id.endsWith("_wood"))for(int[] side:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
                if(blockId(call(world,"method_8320",position(x+side[0],y,z+side[1]))).equals("minecraft:air")){
                    fireX=x+side[0];fireY=y;fireZ=z+side[1];found=true;break search;
                }
            }
        }
        if(!found){if(++waits<4)return;throw new AssertionError("No exposed natural giant-forest trunk within bounded24 block scan");}
        burnBefore=burnSnapshot();startTick=(Long)call(world,"method_8510");startNanos=System.nanoTime();
        Object fireBlock=registryValue("field_41175","minecraft:fire");Object fireState=call(fireBlock,"method_9564");
        call(world,"method_8652",position(fireX,fireY,fireZ),fireState,3);
        burnImmediate=burnSnapshot();
        event("native_giant_forest_fire_ignited",Map.of("biome",biome,"naturalTrunk",true,"x",fireX,"y",fireY,"z",fireZ,"initialStates",counts(burnBefore),"forcedRandomTicks",false,"configOverrides",false));
        phase=12;waits=0;status="observing_native_burnt_ticks";
    }

    private static void observeBurn(Object server,Object player)throws Exception {
        long ticks=(Long)call(world,"method_8510")-startTick;
        Map<String,String> current=burnSnapshot();int changed=0,burnt=0,delayedSpread=0,newlyBurning=0;
        for(var entry:current.entrySet()){
            if(!entry.getValue().equals(burnBefore.get(entry.getKey())))changed++;
            if(!entry.getKey().equals("0,0,0")&&!entry.getValue().equals(burnImmediate.get(entry.getKey())))delayedSpread++;
            if(isBurnt(entry.getValue())){
                burnt++;
                if(!entry.getKey().equals("0,0,0")&&!isBurnt(burnImmediate.get(entry.getKey())))newlyBurning++;
            }
        }
        long seconds=(System.nanoTime()-startNanos)/1_000_000_000L;
        event("burnt_tick_observation",Map.of("elapsedTicks",ticks,"elapsedSeconds",seconds,"changedBlocks",changed,"burntBlocks",burnt,"changedAfterImmediateIgnition",delayedSpread,"newlyConvertedOutsideIgnition",newlyBurning,"states",counts(current)));
        if(ticks>=120&&newlyBurning>=2&&delayedSpread>=2){
            event("burnt_native_spread",Map.of("pass",true,"elapsedTicks",ticks,"burntBlocks",burnt,"changedAfterImmediateIgnition",delayedSpread,"newlyConvertedOutsideIgnition",newlyBurning,"realNaturalGiantForestTree",true,"forcedRandomTicks",false));
            terminal=true;status="COMPLETE native_graves_and_burnt; inspect variant/result details";
        }else if(ticks>=1200||seconds>=120){
            event("burnt_native_spread",Map.of("pass",false,"status","BOUNDED_OBSERVATION_DID_NOT_PROVE_BURNT_SPREAD","elapsedTicks",ticks,"burntBlocks",burnt,"changedAfterImmediateIgnition",delayedSpread,"newlyConvertedOutsideIgnition",newlyBurning));
            terminal=true;status="COMPLETE_WITH_UNPROVEN_BURNT_SPREAD";
        }
    }
    private static boolean isBurnt(String id){return id!=null&&(id.startsWith("burnt:")||id.startsWith("burnt_fabric:"));}
    private static Method nativeMethod(Class<?> owner,String name,int count)throws Exception{for(Class<?> c=owner;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==count){m.setAccessible(true);return m;}throw new NoSuchMethodException(owner+"."+name);}
    private static String nativeBiomeId(Method query,Object source,Object sampler,Method key,Method location,int x,int z)throws Exception{Object holder=query.invoke(source,Math.floorDiv(x,4),25,Math.floorDiv(z,4),sampler);Optional<?> ref=(Optional<?>)key.invoke(holder);return ref.isPresent()?String.valueOf(location.invoke(ref.get())):"<unbound>";}

    private static Map<String,String> burnSnapshot()throws Exception {
        Map<String,String> out=new LinkedHashMap<>();
        for(int dx=-5;dx<=5;dx++)for(int dy=-3;dy<=9;dy++)for(int dz=-5;dz<=5;dz++)out.put(dx+","+dy+","+dz,blockId(call(world,"method_8320",position(fireX+dx,fireY+dy,fireZ+dz))));
        return out;
    }
    private static Map<String,Integer> counts(Map<String,String> states){Map<String,Integer> out=new TreeMap<>();for(String id:states.values())out.merge(id,1,Integer::sum);return out;}
    private static void assertNativeWornLoadout(Object server,Object player,String phase)throws Exception {
        Object harness=staticCall("daot.OdmHarness","getHarness",player);
        if((Boolean)call(harness,"method_7960")||call(harness,"method_7909")!=field("daot.DannysAot","ODM_HARNESS"))
            throw new AssertionError("Native getHarness must find ODM_HARNESS in the actual LEGS equipment slot");
        Object gear=staticCall("daot.OdmHarness","getGear",player);
        if((Boolean)call(gear,"method_7960")||call(gear,"method_7909")!=field("daot.DannysAot","ODM_GEAR")||(Integer)staticCall("daot.DannysAot","getGasFromGear",gear)!=321)
            throw new AssertionError("Native harness must contain its real nested ODM_GEAR with321 gas");
        for(String hand:List.of("method_6047","method_6079")){
            Object grip=call(player,hand);
            if((Boolean)call(grip,"method_7960")||call(grip,"method_7909")!=field("daot.DannysAot","BLADE"))
                throw new AssertionError("Native main/off hand must hold paired grips after equipped restoration");
        }
        Object inv=call(player,"method_31548"),gas=call(inv,"method_5438",1),spares=call(inv,"method_5438",2);
        if(call(gas,"method_7909")!=field("daot.DannysAot","GAS_CANISTER")||(Integer)staticCall("daot.GasCanisterItem","getGas",gas)!=73)
            throw new AssertionError("Control gas canister73 was not preserved");
        if(call(spares,"method_7909")!=field("daot.DannysAot","BLADE_COMPONENT")||(Integer)call(spares,"method_7947")!=16)
            throw new AssertionError("Control16 blade components were not preserved");
        event("native_worn_harness_verified",Map.of("phase",phase,"scenario",scenario,"nativeGetHarness",true,"nestedGearGas",321,"canisterGas",73,"spareBladeComponents",16,"nativeMainAndOffHandGrips",true,"nestedHarnessNbt",stacks(server,List.of(harness))));
    }

    private static Map<String,Integer> inventory(Object server,Object player)throws Exception {
        Object inv=call(player,"method_31548");List<Object> items=new ArrayList<>();int n=(Integer)call(inv,"method_5439");for(int i=0;i<n;i++)items.add(call(inv,"method_5438",i));return stacks(server,items);
    }
    private static Map<String,Integer> graveInventory(Object server,Object grave)throws Exception {
        Object inv=call(grave,"getInventoryComponent");List<Object> items=new ArrayList<>();for(Object item:(Iterable<?>)call(inv,"getItems"))items.add(field(item.getClass(),"stack").get(item));
        for(Object stack:(Iterable<?>)call(inv,"getAllExtraItems",true))items.add(stack);return stacks(server,items);
    }
    private static Map<String,Integer> stacks(Object server,Iterable<?> items)throws Exception {
        Map<String,Integer> out=new TreeMap<>();for(Object stack:items){if((Boolean)call(stack,"method_7960"))continue;int count=(Integer)call(stack,"method_7947");Object copy=call(stack,"method_7972");call(copy,"method_7939",1);String key=String.valueOf(call(copy,"method_57358",call(server,"method_30611")));out.merge(key,count,Integer::sum);}return out;
    }
    private static int matchingDrops(Object server,Object level,Object pos)throws Exception {
        int total=0;int x=coordinate(pos,"method_10263"),y=coordinate(pos,"method_10264"),z=coordinate(pos,"method_10260");
        for(Object entity:(Iterable<?>)call(level,"method_27909"))if(entity.getClass().getName().equals("net.minecraft.class_1542")){
            if(Math.abs((Double)call(entity,"method_23317")-x)>32||Math.abs((Double)call(entity,"method_23318")-y)>32||Math.abs((Double)call(entity,"method_23321")-z)>32)continue;
            Map<String,Integer> items=stacks(server,List.of(call(entity,"method_6983")));for(var e:items.entrySet())if(expected.containsKey(e.getKey())||nestedDropKeys.contains(e.getKey()))total+=e.getValue();
        }return total;
    }
    private static Set<UUID> graveIds()throws Exception {Set<UUID> out=new HashSet<>();for(Object graves:((Map<?,?>)call(graveManager(),"getPlayerGraves")).values())for(Object grave:(Iterable<?>)graves)out.add((UUID)call(grave,"getGraveId"));return out;}
    private static Object graveById(UUID id)throws Exception{return ((Optional<?>)call(graveManager(),"getGrave",id)).orElseThrow();}
    private static Object graveManager()throws Exception{return field("com.b1n_ry.yigd.data.DeathInfoManager","INSTANCE");}
    private static Object stack(Object item,int count)throws Exception{return construct("net.minecraft.class_1799",item,count);}
    private static Object position(int x,int y,int z)throws Exception{return construct("net.minecraft.class_2338",x,y,z);}
    private static int coordinate(Object p,String name)throws Exception{return (Integer)call(p,name);}
    private static String blockId(Object state)throws Exception {Object block=call(state,"method_26204");return String.valueOf(call(field("net.minecraft.class_7923","field_41175"),"method_10221",block));}
    private static Object registryValue(String registry,String id)throws Exception {Object key=staticCall("net.minecraft.class_2960","method_60654",id);return call(field("net.minecraft.class_7923",registry),"method_10223",key);}
    private static void command(Object server,String command)throws Exception {
        Object dispatcher=call(call(server,"method_3734"),"method_9235");
        Object result=call(dispatcher,"execute",command,call(server,"method_3739"));
        // The convenience executeWithPrefix swallows command syntax errors.
        // Brigadier execute propagates rejection and provides an observed result.
        event("native_command_result",Map.of("command",command,"result",String.valueOf(result)));
    }
    private static Object field(String type,String name)throws Exception{return field(Class.forName(type,true,loader),name).get(null);}
    private static Field field(Class<?> type,String name)throws Exception{for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(type+"."+name);}
    private static Object enumValue(String type,String name)throws Exception{for(Object e:Class.forName(type,true,loader).getEnumConstants())if(((Enum<?>)e).name().equals(name))return e;throw new IllegalArgumentException(type+name);}
    private static Object construct(String type,Object...args)throws Exception{for(Constructor<?> c:Class.forName(type,true,loader).getConstructors())if(matches(c.getParameterTypes(),args))return c.newInstance(args);throw new NoSuchMethodException(type+Arrays.toString(args));}
    private static Object staticCall(String type,String name,Object...args)throws Exception{return invoke(Class.forName(type,true,loader),null,name,args);}
    private static Object call(Object obj,String name,Object...args)throws Exception{return invoke(obj.getClass(),obj,name,args);}
    private static Object invoke(Class<?> type,Object obj,String name,Object...args)throws Exception{
        for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&matches(m.getParameterTypes(),args)){m.setAccessible(true);return m.invoke(obj,args);}
        for(Method m:type.getMethods())if(m.getName().equals(name)&&matches(m.getParameterTypes(),args)){m.setAccessible(true);return m.invoke(obj,args);}throw new NoSuchMethodException(type+"."+name+Arrays.toString(args));
    }
    private static boolean matches(Class<?>[] types,Object[] args){if(types.length!=args.length)return false;for(int i=0;i<types.length;i++){if(args[i]==null){if(types[i].isPrimitive())return false;continue;}Class<?> t=types[i];if(t.isPrimitive())t=t==int.class?Integer.class:t==long.class?Long.class:t==double.class?Double.class:t==float.class?Float.class:t==boolean.class?Boolean.class:t;if(!t.isInstance(args[i]))return false;}return true;}
    private static String required(String key){String v=System.getProperty("lanminecraft.qa."+key);if(v==null||v.isBlank())throw new SecurityException("Missing fixture property "+key);return v;}
    private static synchronized void event(String kind,Map<String,?> data)throws Exception{Map<String,Object> row=new LinkedHashMap<>();row.put("utc",Instant.now().toString());row.put("kind",kind);row.putAll(data);results.add(row);Files.writeString(report,json(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);status=kind;}
    private static String json(Object value){if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();if(value instanceof Map<?,?> map){StringJoiner s=new StringJoiner(",","{","}");for(var e:map.entrySet())s.add(json(e.getKey().toString())+":"+json(e.getValue()));return s.toString();}if(value instanceof Iterable<?> list){StringJoiner s=new StringJoiner(",","[","]");for(Object v:list)s.add(json(v));return s.toString();}return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
}
