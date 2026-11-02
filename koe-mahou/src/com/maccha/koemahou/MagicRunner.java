package com.maccha.koemahou;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.maccha.koemahou.SpellEffects.explodeSafely;
import static com.maccha.koemahou.SpellEffects.hurt;
import static com.maccha.koemahou.SpellEffects.later;
import static com.maccha.koemahou.SpellEffects.mobsAround;
import static com.maccha.koemahou.SpellEffects.playSound;

/**
 * 効果の部品を、上から順番に動かす（サーバーの中で動く）
 */
public class MagicRunner {

    // パソコンが重くならないための上限
    static final int MAX_TICKS = 20 * 60;        // 1つの魔法は60秒まで
    static final int MAX_STEPS = 6000;           // 部品を動かす回数
    static final double MAX_RADIUS = 48;         // 半径
    static final float MAX_EXPLOSION = 12;       // 爆発の大きさ
    static final int MAX_SPAWN = 30;             // よぶ・撃つ の数

    // 粒（パーティクル）の日本語の名前
    static final Map<String, String> PARTICLE_NAMES = Map.ofEntries(
            Map.entry("炎", "flame"), Map.entry("青い炎", "soul_fire_flame"), Map.entry("煙", "smoke"),
            Map.entry("大きな煙", "large_smoke"), Map.entry("闇", "squid_ink"), Map.entry("ポータル", "portal"),
            Map.entry("逆ポータル", "reverse_portal"), Map.entry("光", "end_rod"), Map.entry("ハート", "heart"),
            Map.entry("緑", "happy_villager"), Map.entry("電気", "electric_spark"), Map.entry("魔法", "enchant"),
            Map.entry("溶岩", "lava"), Map.entry("爆発", "explosion"), Map.entry("大爆発", "explosion_emitter"),
            Map.entry("雪", "snowflake"), Map.entry("泡", "bubble"), Map.entry("トーテム", "totem_of_undying"),
            Map.entry("灰", "ash"), Map.entry("衝撃波", "sonic_boom"), Map.entry("水", "splash"),
            Map.entry("音符", "note"), Map.entry("怒り", "angry_villager"), Map.entry("クリティカル", "crit"),
            Map.entry("星", "wax_off"), Map.entry("雲", "cloud"), Map.entry("ドラゴン", "dragon_breath"),
            Map.entry("魂", "soul"), Map.entry("火花", "lava"), Map.entry("毒", "witch"));

    // 音の日本語の名前
    static final Map<String, String> SOUND_NAMES = Map.ofEntries(
            Map.entry("爆発", "entity.generic.explode"), Map.entry("雷", "entity.lightning_bolt.thunder"),
            Map.entry("ベル", "block.bell.use"), Map.entry("レベルアップ", "entity.player.levelup"),
            Map.entry("トーテム", "item.totem.use"), Map.entry("ワープ", "entity.enderman.teleport"),
            Map.entry("ブレイズ", "entity.blaze.shoot"), Map.entry("ウォーデン", "entity.warden.sonic_boom"),
            Map.entry("ビーコン", "block.beacon.activate"), Map.entry("キラキラ", "block.amethyst_block.chime"),
            Map.entry("ドラゴン", "entity.ender_dragon.growl"), Map.entry("ウィザー", "entity.wither.spawn"),
            Map.entry("ポータル", "block.portal.trigger"), Map.entry("火", "item.firecharge.use"),
            Map.entry("風", "entity.breeze.wind_burst"), Map.entry("ガラス", "block.glass.break"));

    // ポーション効果の日本語の名前
    static final Map<String, String> EFFECT_NAMES = Map.ofEntries(
            Map.entry("再生", "regeneration"), Map.entry("衝撃吸収", "absorption"), Map.entry("耐性", "resistance"),
            Map.entry("力", "strength"), Map.entry("スピード", "speed"), Map.entry("跳躍", "jump_boost"),
            Map.entry("透明", "invisibility"), Map.entry("暗視", "night_vision"), Map.entry("発光", "glowing"),
            Map.entry("鈍足", "slowness"), Map.entry("弱体化", "weakness"), Map.entry("毒", "poison"),
            Map.entry("盲目", "blindness"), Map.entry("浮遊", "levitation"), Map.entry("低速落下", "slow_falling"),
            Map.entry("火炎耐性", "fire_resistance"), Map.entry("水中呼吸", "water_breathing"),
            Map.entry("採掘速度上昇", "haste"), Map.entry("ウィザー", "wither"), Map.entry("暗闇", "darkness"));

    // 撃つ・よぶ もの の日本語の名前
    static final Map<String, String> ENTITY_NAMES = Map.ofEntries(
            Map.entry("火の玉", "small_fireball"), Map.entry("大きな火の玉", "fireball"),
            Map.entry("ウィザーの頭", "wither_skull"), Map.entry("ドラゴンの火の玉", "dragon_fireball"),
            Map.entry("矢", "arrow"), Map.entry("雪玉", "snowball"), Map.entry("風の弾", "wind_charge"),
            Map.entry("シュルカーの弾", "shulker_bullet"), Map.entry("TNT", "tnt"),
            Map.entry("ゾンビ", "zombie"), Map.entry("スケルトン", "skeleton"), Map.entry("オオカミ", "wolf"),
            Map.entry("アイアンゴーレム", "iron_golem"), Map.entry("スノーゴーレム", "snow_golem"),
            Map.entry("ネコ", "cat"), Map.entry("ブタ", "pig"), Map.entry("ニワトリ", "chicken"),
            Map.entry("クリーパー", "creeper"), Map.entry("ヴェックス", "vex"), Map.entry("ミツバチ", "bee"),
            Map.entry("コウモリ", "bat"), Map.entry("ウシ", "cow"), Map.entry("ヒツジ", "sheep"),
            Map.entry("キツネ", "fox"), Map.entry("アレイ", "allay"), Map.entry("エヴォーカーの牙", "evoker_fangs"));

    // ブロックの日本語の名前（壁・ドーム・足場）
    static final Map<String, String> BLOCK_NAMES = Map.ofEntries(
            Map.entry("氷", "ice"), Map.entry("青い氷", "blue_ice"), Map.entry("ガラス", "glass"), Map.entry("石", "stone"),
            Map.entry("黒曜石", "obsidian"), Map.entry("光る石", "glowstone"), Map.entry("雪", "snow_block"),
            Map.entry("クモの巣", "cobweb"), Map.entry("葉っぱ", "oak_leaves"), Map.entry("金", "gold_block"),
            Map.entry("ダイヤ", "diamond_block"), Map.entry("土", "dirt"), Map.entry("マグマ", "magma_block"),
            Map.entry("木", "oak_planks"), Map.entry("スライム", "slime_block"), Map.entry("アメジスト", "amethyst_block"));

    /** 魔法1つぶんの、今の状態 */
    static class State {
        final ServerLevel level;
        final ServerPlayer player;
        // 今の場所（「場所」で変わる）と、「もどる」で もどる場所
        Vec3 point;
        Vec3 base;
        // いつ動かすか（tick）
        int time = 0;
        int steps = 0;
        // 「場所」で自分以外の所にしたか
        boolean pointMoved = false;
        // 今の向き（「向き」で変わる。はじめは見ている方）
        Vec3 direction;
        // 「確率」で決めた、次のピースが動くパーセント（-1 は いつも動く）
        double chance = -1;

        State(ServerLevel level, ServerPlayer player) {
            this.level = level;
            this.player = player;
            this.point = player.position();
            this.base = point;
            this.direction = player.getLookAngle();
        }
    }

    /** 部品の文を読んで、魔法を出す */
    public static void cast(ServerLevel level, ServerPlayer player, String program) {
        List<String> problems = new ArrayList<>();
        List<String> lines = List.of(program.split("\n"));
        List<Magic.Node> nodes = Magic.parse(lines, List.of(), "魔法", problems);
        State state = new State(level, player);
        run(nodes, state);
        if (state.steps >= MAX_STEPS || state.time >= MAX_TICKS) {
            player.sendSystemMessage(Component.literal("§7こえまほう：魔法が大きすぎるので、とちゅうまでにしました（60秒・部品6000回まで）"));
        }
    }

    /** 部品の並びを、上から順番に動かす */
    static void run(List<Magic.Node> nodes, State state) {
        for (Magic.Node node : nodes) {
            if (state.steps >= MAX_STEPS || state.time >= MAX_TICKS) return;
            state.steps++;
            Magic.Part part = node.part();
            // 確率：次のピースを、そのパーセントで動かす（動かさないときは とばす）
            if (part.name().equals("確率")) {
                state.chance = clamp(part.number(0, 50), 0, 100);
                continue;
            }
            if (state.chance >= 0) {
                boolean skip = state.level.getRandom().nextDouble() * 100 >= state.chance;
                state.chance = -1;
                if (skip) continue;
            }
            if (part.name().equals("くりかえす")) {
                int count = (int) clamp(part.number(0, 1), 1, 1000);
                int interval = ticks(part.number(1, 0));
                for (int round = 0; round < count; round++) {
                    run(node.children(), state);
                    state.time += interval;
                    if (state.steps >= MAX_STEPS || state.time >= MAX_TICKS) return;
                }
            } else {
                runPart(part, state);
            }
        }
    }

    /** 1つの部品を動かす */
    static void runPart(Magic.Part part, State state) {
        ServerLevel level = state.level;
        ServerPlayer player = state.player;
        Vec3 at = state.point;
        int time = state.time;
        switch (part.name()) {
            // ===== 場所と時間 =====
            case "場所" -> movePoint(part, state);
            case "向き" -> turn(part, state);
            case "待つ" -> state.time += ticks(part.number(0, 1));
            case "待ち時間" -> { }

            // ===== こうげき =====
            case "爆発" -> {
                float power = (float) clamp(part.number(0, 3), 0.5, MAX_EXPLOSION);
                later(time, () -> explodeSafely(level, player, at, power));
            }
            case "ダメージ" -> {
                float amount = (float) clamp(part.number(0, 5), 0, 1000);
                double radius = radius(part.number(1, 4));
                later(time, () -> mobsAround(level, player, at, radius).forEach(mob -> hurt(level, player, mob, amount)));
            }
            case "燃やす" -> {
                double seconds = clamp(part.number(0, 5), 0, 60);
                double radius = radius(part.number(1, 4));
                later(time, () -> mobsAround(level, player, at, radius).forEach(mob -> mob.igniteForSeconds((float) seconds)));
            }
            case "雷" -> {
                int count = (int) clamp(part.number(0, 1), 1, MAX_SPAWN);
                double radius = radius(part.number(1, 0));
                later(time, () -> {
                    for (int index = 0; index < count; index++) BigSpells.bolt(level, randomAround(level, at, radius));
                });
            }

            // ===== うごかす =====
            case "吸いこむ" -> {
                double radius = radius(part.number(0, 10));
                int duration = Math.max(1, ticks(part.number(1, 3)));
                double strength = clamp(part.number(2, 1), 0, 5);
                for (int tick = 0; tick < duration; tick++) {
                    later(time + tick, () -> {
                        for (LivingEntity mob : mobsAround(level, player, at, radius)) {
                            Vec3 toCenter = at.subtract(mob.position().add(0, mob.getBbHeight() / 2, 0));
                            double distance = toCenter.length();
                            double speed = Math.min(1.2, 0.25 + 3.0 / Math.max(distance, 1.0)) * strength;
                            mob.setDeltaMovement(toCenter.normalize().scale(speed));
                            mob.needsSync = true;
                            mob.resetFallDistance();
                        }
                    });
                }
            }
            case "吹きとばす" -> {
                double radius = radius(part.number(0, 8));
                double strength = clamp(part.number(1, 2), 0, 10);
                later(time, () -> {
                    for (LivingEntity mob : mobsAround(level, player, at, radius)) {
                        Vec3 push = mob.position().subtract(at).normalize().scale(strength);
                        mob.setDeltaMovement(push.x, 0.4 + strength * 0.3, push.z);
                        mob.needsSync = true;
                    }
                });
            }
            case "押す" -> {
                // 今の向きへ、モブをまとめて押す
                double radius = radius(part.number(0, 8));
                double strength = clamp(part.number(1, 2), 0, 10);
                Vec3 push = state.direction.scale(strength);
                later(time, () -> {
                    for (LivingEntity mob : mobsAround(level, player, at, radius)) {
                        mob.setDeltaMovement(push.x, push.y + 0.2, push.z);
                        mob.needsSync = true;
                    }
                });
            }
            case "うかせる" -> {
                double radius = radius(part.number(0, 8));
                int duration = Math.max(1, ticks(part.number(1, 3)));
                double height = clamp(part.number(2, 4), 0, 30);
                for (int tick = 0; tick < duration; tick++) {
                    later(time + tick, () -> {
                        for (LivingEntity mob : mobsAround(level, player, at, radius + height)) {
                            Vec3 hover = new Vec3(mob.getX(), at.y + height, mob.getZ());
                            mob.setDeltaMovement(hover.subtract(mob.position()).scale(0.3));
                            mob.needsSync = true;
                            mob.resetFallDistance();
                        }
                    });
                }
            }
            case "止める" -> freeze(level, player, at, radius(part.number(0, 8)), Math.max(1, ticks(part.number(1, 3))), time);

            // ===== 自分・仲間 =====
            case "回復" -> {
                float amount = (float) clamp(part.number(0, 4), 0, 1000);
                double radius = clamp(part.number(1, 0), 0, MAX_RADIUS);
                later(time, () -> playersAround(level, player, at, radius).forEach(friend -> friend.heal(amount)));
            }
            case "効果" -> {
                Optional<Holder.Reference<MobEffect>> effect = BuiltInRegistries.MOB_EFFECT.get(id(EFFECT_NAMES, part.word(0, "")));
                if (effect.isEmpty()) {
                    unknown(player, part, "効果");
                    return;
                }
                int duration = ticks(clamp(part.number(1, 5), 0, 3600));
                int amplifier = (int) clamp(part.number(2, 1), 1, 10) - 1;
                String who = part.word(3, "自分");
                double radius = radius(part.number(4, 8));
                later(time, () -> {
                    List<? extends LivingEntity> targets = switch (who) {
                        case "仲間" -> playersAround(level, player, at, radius);
                        case "モブ" -> mobsAround(level, player, at, radius);
                        default -> List.of(player);
                    };
                    for (LivingEntity target : targets) target.addEffect(new MobEffectInstance(effect.get(), duration, amplifier));
                });
            }
            case "ワープ" -> {
                // 数字があれば、今の向きへその距離。なければ「場所」で決めた所へ
                boolean toPoint = part.args().isEmpty() && state.pointMoved;
                double distance = clamp(part.number(0, 10), 1, 64);
                Vec3 direction = state.direction;
                later(time, () -> {
                    if (toPoint) {
                        teleportTo(level, player, at);
                    } else {
                        warpToward(level, player, direction, distance);
                    }
                });
            }
            // ===== 撃つ・よぶ =====
            case "撃つ" -> {
                Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id(ENTITY_NAMES, part.word(0, "")));
                if (type.isEmpty()) {
                    unknown(player, part, "撃つもの");
                    return;
                }
                int count = (int) clamp(part.number(1, 1), 1, MAX_SPAWN);
                double speed = clamp(part.number(2, 1.5), 0.1, 6);
                double spread = clamp(part.number(3, 5), 0, 90);
                // 「場所」を決めていなければ自分の目から、決めていればその場所から、今の向きへ
                Vec3 from = state.pointMoved ? at.add(0, 1, 0) : null;
                Vec3 direction = state.direction;
                later(time, () -> shoot(level, player, type.get(), count, speed, spread, from == null ? player.getEyePosition() : from, direction));
            }
            case "飛ばす" -> fly(part, state);
            case "ビーム" -> {
                ParticleOptions particle = particle(player, part);
                if (particle == null) return;
                double length = clamp(part.number(1, 20), 1, 64);
                float damage = (float) clamp(part.number(2, 10), 0, 1000);
                Vec3 from = state.pointMoved ? at.add(0, 1, 0) : null;
                Vec3 direction = state.direction;
                later(time, () -> beam(level, player, particle, from == null ? player.getEyePosition() : from, direction, length, damage));
            }
            case "よぶ" -> {
                Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id(ENTITY_NAMES, part.word(0, "")));
                if (type.isEmpty()) {
                    unknown(player, part, "よぶもの");
                    return;
                }
                int count = (int) clamp(part.number(1, 1), 1, MAX_SPAWN);
                later(time, () -> {
                    for (int index = 0; index < count; index++) {
                        Entity entity = type.get().create(level, EntitySpawnReason.MOB_SUMMONED);
                        if (entity == null) return;
                        Vec3 spot = randomAround(level, at, count > 1 ? 2 : 0);
                        entity.snapTo(spot.x, spot.y, spot.z, player.getYRot(), 0);
                        if (entity instanceof Projectile projectile) projectile.setOwner(player);
                        level.addFreshEntity(entity);
                    }
                });
            }

            // ===== 見た目と音 =====
            case "粒" -> {
                ParticleOptions particle = particle(player, part);
                if (particle == null) return;
                int count = (int) clamp(part.number(1, 20), 1, 500);
                double spread = clamp(part.number(2, 1), 0, MAX_RADIUS);
                later(time, () -> level.sendParticles(particle, at.x, at.y + 1, at.z, count, spread, spread, spread, 0.05));
            }
            case "輪" -> {
                ParticleOptions particle = particle(player, part);
                if (particle == null) return;
                double radius = radius(part.number(1, 3));
                later(time, () -> ring(level, particle, at.add(0, 0.2, 0), radius, 0));
            }
            case "魔法陣" -> {
                ParticleOptions particle = particle(player, part);
                if (particle == null) return;
                double radius = radius(part.number(1, 5));
                int age = time;
                later(time, () -> BigSpells.magicCircle(level, at.add(0, 0.2, 0), radius, age, particle));
            }
            case "柱" -> {
                ParticleOptions particle = particle(player, part);
                if (particle == null) return;
                double height = clamp(part.number(1, 10), 0, 64);
                later(time, () -> {
                    for (double y = 0; y < height; y += 0.5) level.sendParticles(particle, at.x, at.y + y, at.z, 3, 0.3, 0.1, 0.3, 0);
                });
            }
            case "球" -> {
                ParticleOptions particle = particle(player, part);
                if (particle == null) return;
                double size = clamp(part.number(1, 1.5), 0.1, 20);
                later(time, () -> level.sendParticles(particle, at.x, at.y, at.z, (int) (20 + size * 25), size * 0.4, size * 0.4, size * 0.4, 0));
            }
            case "線" -> {
                ParticleOptions particle = particle(player, part);
                if (particle == null) return;
                Vec3 from = player.getEyePosition();
                later(time, () -> {
                    Vec3 line = at.subtract(from);
                    int dots = (int) Math.min(300, line.length() * 2);
                    for (int index = 1; index <= dots; index++) {
                        Vec3 dot = from.add(line.scale((double) index / dots));
                        level.sendParticles(particle, dot.x, dot.y, dot.z, 1, 0, 0, 0, 0);
                    }
                });
            }
            case "音" -> {
                Optional<Holder.Reference<SoundEvent>> sound = BuiltInRegistries.SOUND_EVENT.get(id(SOUND_NAMES, part.word(0, "")));
                if (sound.isEmpty()) {
                    unknown(player, part, "音");
                    return;
                }
                float volume = (float) clamp(part.number(1, 1), 0, 10);
                float pitch = (float) clamp(part.number(2, 1), 0.5, 2);
                later(time, () -> playSound(level, at, sound.get().value(), volume, pitch));
            }

            // ===== ふえたピース =====
            case "凍らせる" -> {
                double radius = radius(part.number(0, 8));
                int duration = Math.max(1, ticks(part.number(1, 5)));
                later(time, () -> {
                    for (LivingEntity mob : mobsAround(level, player, at, radius)) {
                        mob.addEffect(new MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOWNESS, duration, 4));
                        mob.setTicksFrozen(Math.max(mob.getTicksFrozen(), 140 + duration));
                        level.sendParticles(ParticleTypes.SNOWFLAKE, mob.getX(), mob.getY() + 1, mob.getZ(), 15, 0.4, 0.6, 0.4, 0.02);
                    }
                    playSound(level, at, net.minecraft.sounds.SoundEvents.GLASS_BREAK, 1.0f, 1.5f);
                });
            }
            case "引きよせる" -> {
                double radius = radius(part.number(0, 12));
                double strength = clamp(part.number(1, 1.5), 0.1, 5);
                later(time, () -> {
                    for (LivingEntity mob : mobsAround(level, player, at, radius)) {
                        Vec3 pull = player.position().subtract(mob.position()).normalize().scale(strength);
                        mob.setDeltaMovement(pull.x, 0.3, pull.z);
                        mob.needsSync = true;
                    }
                });
            }
            case "うず", "竜巻" -> {
                // まわりを回す。竜巻は うかせながら
                boolean tornado = part.name().equals("竜巻");
                double radius = radius(part.number(0, 8));
                int duration = Math.max(1, ticks(part.number(1, 3)));
                double third = part.number(2, tornado ? 8 : 1);
                for (int tick = 0; tick < duration; tick++) {
                    int age = tick;
                    later(time + tick, () -> {
                        for (LivingEntity mob : mobsAround(level, player, at, radius + (tornado ? third : 0))) {
                            Vec3 out = new Vec3(mob.getX() - at.x, 0, mob.getZ() - at.z);
                            if (out.lengthSqr() < 0.01) out = new Vec3(1, 0, 0);
                            Vec3 side = new Vec3(-out.z, 0, out.x).normalize();
                            double speed = tornado ? 0.6 : clamp(third, 0.1, 5) * 0.5;
                            Vec3 inward = out.normalize().scale(-0.15);
                            double up = tornado ? (mob.getY() < at.y + third ? 0.35 : 0.0) : mob.getDeltaMovement().y;
                            mob.setDeltaMovement(side.x * speed + inward.x, up, side.z * speed + inward.z);
                            mob.needsSync = true;
                            mob.resetFallDistance();
                        }
                        if (age % 2 == 0) helix(level, tornado ? ParticleTypes.CLOUD : ParticleTypes.PORTAL, at, radius * 0.6, tornado ? third : 1.5, age * 0.4);
                    });
                }
            }
            case "衝撃波" -> {
                // 1tick に1ブロックずつ広がる輪。輪がとおったモブにダメージ
                double radius = radius(part.number(0, 10));
                float damage = (float) clamp(part.number(1, 8), 0, 1000);
                java.util.Set<LivingEntity> hit = new java.util.HashSet<>();
                for (int ring = 1; ring <= (int) radius; ring++) {
                    double size = ring;
                    later(time + ring, () -> {
                        ring(level, ParticleTypes.CLOUD, at.add(0, 0.3, 0), size, 0);
                        for (LivingEntity mob : mobsAround(level, player, at, size)) {
                            if (!hit.add(mob)) continue;
                            hurt(level, player, mob, damage);
                            Vec3 push = mob.position().subtract(at).normalize().scale(1.2);
                            mob.setDeltaMovement(push.x, 0.5, push.z);
                            mob.needsSync = true;
                        }
                    });
                }
                later(time, () -> playSound(level, at, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), 1.0f, 0.6f));
            }
            case "たたきつける" -> {
                double radius = radius(part.number(0, 8));
                float damage = (float) clamp(part.number(1, 10), 0, 1000);
                later(time, () -> {
                    for (LivingEntity mob : mobsAround(level, player, at, radius)) {
                        mob.setDeltaMovement(0, -3, 0);
                        mob.needsSync = true;
                        hurt(level, player, mob, damage);
                        level.sendParticles(ParticleTypes.EXPLOSION, mob.getX(), mob.getY(), mob.getZ(), 1, 0, 0, 0, 0);
                    }
                });
            }
            case "連鎖雷" -> {
                int count = (int) clamp(part.number(0, 5), 1, MAX_SPAWN);
                float damage = (float) clamp(part.number(1, 8), 0, 1000);
                later(time, () -> chainLightning(level, player, at, count, damage, new java.util.HashSet<>()));
            }
            case "隕石" -> {
                double size = clamp(part.number(0, 2), 0.5, 5);
                float damage = (float) clamp(part.number(1, 20), 0, 1000);
                BigSpells.fallingMeteor(level, player, at.add(3, 30, 3), at, time, 20, size, (float) Math.min(MAX_EXPLOSION, 2 + size * 1.5), damage, 2 + size * 2);
            }
            case "牙" -> {
                // 向きの方へ、地面から牙が1本ずつ
                int count = (int) clamp(part.number(0, 8), 1, MAX_SPAWN);
                Vec3 flat = new Vec3(state.direction.x, 0, state.direction.z);
                Vec3 step = flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize().scale(1.25);
                float yaw = (float) Math.toDegrees(Math.atan2(step.z, step.x));
                Vec3 from = state.pointMoved ? at : player.position();
                later(time, () -> {
                    for (int index = 1; index <= count; index++) {
                        Vec3 spot = from.add(step.scale(index));
                        level.addFreshEntity(new net.minecraft.world.entity.projectile.EvokerFangs(level, spot.x, spot.y, spot.z, yaw, index, player));
                    }
                });
            }
            case "雨" -> {
                Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id(ENTITY_NAMES, part.word(0, "")));
                if (type.isEmpty()) {
                    unknown(player, part, "降らせるもの");
                    return;
                }
                int count = (int) clamp(part.number(1, 10), 1, MAX_SPAWN);
                double radius = radius(part.number(2, 5));
                double height = clamp(part.number(3, 15), 2, 60);
                later(time, () -> {
                    for (int index = 0; index < count; index++) {
                        Vec3 spot = randomAround(level, at, radius).add(0, height + level.getRandom().nextDouble() * 4, 0);
                        Entity entity = type.get().create(level, EntitySpawnReason.TRIGGERED);
                        if (entity == null) return;
                        entity.snapTo(spot.x, spot.y, spot.z, 0, 90);
                        if (entity instanceof Projectile projectile) projectile.setOwner(player);
                        entity.setDeltaMovement(0, -1.5, 0);
                        level.addFreshEntity(entity);
                    }
                });
            }
            case "ダッシュ" -> {
                double strength = clamp(part.number(0, 2), 0.2, 6);
                Vec3 push = state.direction.scale(strength);
                later(time, () -> {
                    player.setDeltaMovement(push.x, Math.max(push.y, 0.2), push.z);
                    player.needsSync = true;
                    player.resetFallDistance();
                    level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.5, player.getZ(), 15, 0.3, 0.3, 0.3, 0.05);
                });
            }
            case "飛ぶ" -> {
                double height = clamp(part.number(0, 8), 1, 60);
                double speed = Math.sqrt(0.18 * height) + 0.1;
                later(time, () -> {
                    player.setDeltaMovement(player.getDeltaMovement().x, speed, player.getDeltaMovement().z);
                    player.needsSync = true;
                    player.resetFallDistance();
                    player.addEffect(new MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 60 + (int) (height * 3), 0));
                    level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(), 20, 0.5, 0.1, 0.5, 0.05);
                });
            }
            case "壁", "ドーム", "足場" -> {
                net.minecraft.world.level.block.Block block = block(player, part);
                if (block == null) return;
                double seconds = clamp(part.number(part.name().equals("壁") ? 3 : 2, 10), 1, 120);
                List<BlockPos> spots = switch (part.name()) {
                    case "壁" -> wallSpots(at, state.direction, (int) clamp(part.number(1, 5), 1, 21), (int) clamp(part.number(2, 3), 1, 12));
                    case "ドーム" -> domeSpots(at, clamp(part.number(1, 4), 2, 10));
                    default -> diskSpots(at, clamp(part.number(1, 3), 1, 10));
                };
                later(time, () -> placeTemporary(level, block, spots, ticks(seconds)));
            }
            case "らせん" -> {
                ParticleOptions particle = particle(player, part);
                if (particle == null) return;
                double radius = radius(part.number(1, 2));
                double height = clamp(part.number(2, 5), 0.5, 64);
                later(time, () -> helix(level, particle, at, radius, height, 0));
            }
            case "文字" -> {
                String text = part.word(0, "").replace('_', ' ');
                later(time, () -> {
                    for (Player friend : playersAround(level, player, at, 32)) {
                        if (friend instanceof ServerPlayer serverFriend) serverFriend.sendOverlayMessage(Component.literal("§d" + text));
                    }
                });
            }

            // ===== 前の版の大魔法（部品1つで出る） =====
            case "ファイアボール" -> later(time, () -> SpellEffects.castFireball(level, player));
            case "ヒール" -> later(time, () -> SpellEffects.castHeal(level, player));
            case "ブラックホール" -> later(time, () -> BigSpells.blackHole(level, player));
            case "メテオ" -> later(time, () -> BigSpells.meteorStorm(level, player));
            case "ジャッジメント" -> later(time, () -> BigSpells.judgment(level, player));
            case "サンクチュアリ" -> later(time, () -> BigSpells.sanctuary(level, player));
            case "クロノスタシス" -> later(time, () -> BigSpells.chronostasis(level, player));
            default -> { }
        }
    }

    /** らせんの粒（下から上へ、2本） */
    static void helix(ServerLevel level, ParticleOptions particle, Vec3 center, double radius, double height, double turn) {
        int dots = (int) Math.min(200, height * 10);
        for (int index = 0; index < dots; index++) {
            double y = height * index / dots;
            double angle = turn + y * 2.5;
            for (int arm = 0; arm < 2; arm++) {
                double a = angle + arm * Math.PI;
                level.sendParticles(particle, center.x + Math.cos(a) * radius, center.y + y, center.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
            }
        }
    }

    /** 連鎖雷：いちばん近いモブに雷、そこから次のモブへ 0.15秒ごとに とびうつる */
    static void chainLightning(ServerLevel level, ServerPlayer player, Vec3 from, int left, float damage, java.util.Set<LivingEntity> hit) {
        if (left <= 0) return;
        LivingEntity next = mobsAround(level, player, from, hit.isEmpty() ? 16 : 8).stream()
                .filter(mob -> !hit.contains(mob))
                .min(java.util.Comparator.comparingDouble(mob -> mob.position().distanceToSqr(from))).orElse(null);
        if (next == null) return;
        hit.add(next);
        Vec3 spot = next.position();
        // 前の場所から線をひく
        Vec3 line = spot.add(0, 1, 0).subtract(from.add(0, 1, 0));
        for (double step = 0; step <= line.length(); step += 0.4) {
            Vec3 dot = from.add(0, 1, 0).add(line.normalize().scale(step));
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, dot.x, dot.y, dot.z, 2, 0.05, 0.05, 0.05, 0);
        }
        BigSpells.bolt(level, spot);
        hurt(level, player, next, damage);
        later(3, () -> chainLightning(level, player, spot, left - 1, damage, hit));
    }

    /** ブロックの名前から、ブロックを作る */
    static net.minecraft.world.level.block.Block block(ServerPlayer player, Magic.Part part) {
        Optional<net.minecraft.world.level.block.Block> found = BuiltInRegistries.BLOCK.getOptional(id(BLOCK_NAMES, part.word(0, "")));
        if (found.isPresent() && found.get() != net.minecraft.world.level.block.Blocks.AIR) return found.get();
        unknown(player, part, "ブロック");
        return null;
    }

    /** 壁の場所：向きに 横むきの、幅×高さ（場所の すぐ上から） */
    static List<BlockPos> wallSpots(Vec3 at, Vec3 direction, int width, int height) {
        Vec3 flat = new Vec3(direction.x, 0, direction.z);
        if (flat.lengthSqr() < 1e-6) flat = new Vec3(0, 0, 1);
        Vec3 side = new Vec3(-flat.z, 0, flat.x).normalize();
        List<BlockPos> spots = new ArrayList<>();
        for (int across = 0; across < width; across++) {
            Vec3 column = at.add(side.scale(across - (width - 1) / 2.0));
            for (int up = 0; up < height; up++) spots.add(BlockPos.containing(column.x, at.y + up, column.z));
        }
        return spots;
    }

    /** ドームの場所：場所を中心にした、半分の球のから */
    static List<BlockPos> domeSpots(Vec3 at, double radius) {
        List<BlockPos> spots = new ArrayList<>();
        int size = (int) Math.ceil(radius);
        BlockPos center = BlockPos.containing(at);
        for (int x = -size; x <= size; x++) {
            for (int y = 0; y <= size; y++) {
                for (int z = -size; z <= size; z++) {
                    double distance = Math.sqrt(x * x + y * y + z * z);
                    if (distance <= radius && distance > radius - 1) spots.add(center.offset(x, y, z));
                }
            }
        }
        return spots;
    }

    /** 足場の場所：場所の すぐ下の、丸い床 */
    static List<BlockPos> diskSpots(Vec3 at, double radius) {
        List<BlockPos> spots = new ArrayList<>();
        int size = (int) Math.ceil(radius);
        BlockPos center = BlockPos.containing(at).below();
        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                if (x * x + z * z <= radius * radius) spots.add(center.offset(x, 0, z));
            }
        }
        return spots;
    }

    /** 空気のところだけにブロックを置いて、duration tick 後に 空気にもどす（もとのブロックはこわさない） */
    static void placeTemporary(ServerLevel level, net.minecraft.world.level.block.Block block, List<BlockPos> spots, int duration) {
        List<BlockPos> placed = new ArrayList<>();
        net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState();
        for (BlockPos spot : spots) {
            if (placed.size() >= 2000) break;
            if (!level.getBlockState(spot).isAir()) continue;
            level.setBlock(spot, state, 3, 512);
            placed.add(spot);
        }
        later(duration, () -> {
            for (BlockPos spot : placed) {
                if (level.getBlockState(spot).is(block)) level.setBlock(spot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3, 512);
            }
        });
    }

    /** ビーム：from から向きへ、まっすぐな光線。線の近くのモブにダメージ */
    static void beam(ServerLevel level, ServerPlayer player, ParticleOptions particle, Vec3 from, Vec3 direction, double length, float damage) {
        Vec3 end = from.add(direction.scale(length));
        for (double step = 0; step <= length; step += 0.3) {
            Vec3 dot = from.add(direction.scale(step));
            level.sendParticles(particle, dot.x, dot.y, dot.z, 2, 0.05, 0.05, 0.05, 0);
        }
        AABB box = new AABB(from, end).inflate(1.5);
        for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, box, entity -> !(entity instanceof Player) && entity.isAlive())) {
            // 線からの近さ（1.5ブロック以内）
            Vec3 toMob = mob.position().add(0, mob.getBbHeight() / 2, 0).subtract(from);
            double along = Math.max(0, Math.min(length, toMob.dot(direction)));
            if (toMob.subtract(direction.scale(along)).length() <= 1.5) hurt(level, player, mob, damage);
        }
    }

    /** 飛ばす：粒の玉が 今の向きへ飛んでいき、壁かモブに当たった所が 次の場所になる（そのあとのピースは 当たってから動く） */
    static void fly(Magic.Part part, State state) {
        ServerLevel level = state.level;
        ServerPlayer player = state.player;
        ParticleOptions particle = particle(player, part);
        if (particle == null) return;
        double speed = clamp(part.number(1, 20), 2, 80) / 20.0;
        double maxDistance = clamp(part.number(2, 40), 2, 64);
        Vec3 from = state.pointMoved ? state.point.add(0, 1, 0) : player.getEyePosition();
        Vec3 direction = state.direction.normalize();

        // 壁に当たるところ
        Vec3 end = from.add(direction.scale(maxDistance));
        HitResult wall = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double distance = wall.getType() == HitResult.Type.MISS ? maxDistance : wall.getLocation().distanceTo(from);

        // それより手前のモブに当たるところ（いちばん近いもの）
        AABB box = new AABB(from, end).inflate(1.5);
        for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, box, entity -> !(entity instanceof Player) && entity.isAlive())) {
            Vec3 toMob = mob.position().add(0, mob.getBbHeight() / 2, 0).subtract(from);
            double along = toMob.dot(direction);
            if (along < 0 || along > distance) continue;
            if (toMob.subtract(direction.scale(along)).length() <= 1.2) distance = along;
        }

        // 玉が飛んでいく見た目（1tick ごとに すすむ）
        int flight = Math.max(1, (int) Math.ceil(distance / speed));
        double totalDistance = distance;
        for (int tick = 0; tick <= flight; tick++) {
            double travelled = Math.min(totalDistance, tick * speed);
            Vec3 ball = from.add(direction.scale(travelled));
            later(state.time + tick, () -> level.sendParticles(particle, ball.x, ball.y, ball.z, 6, 0.12, 0.12, 0.12, 0.01));
        }

        // 当たった所（少し手前）が 次の場所。そのあとのピースは 当たってから
        Vec3 hit = from.add(direction.scale(Math.max(0, distance - 0.4)));
        state.point = hit.add(0, -1, 0);
        state.pointMoved = true;
        state.time += flight;
    }

    /** ワープ：向きの方へ、壁の手前まで */
    static void warpToward(ServerLevel level, ServerPlayer player, Vec3 direction, double maxDistance) {
        Vec3 start = player.position();
        for (double step = maxDistance; step >= 1.0; step -= 0.5) {
            Vec3 move = direction.scale(step);
            if (level.noCollision(player, player.getBoundingBox().move(move))) {
                teleportTo(level, player, start.add(move));
                return;
            }
        }
        SpellEffects.fizzle(level, player);
    }

    /** 「向き」：今の向きを変える */
    static void turn(Magic.Part part, State state) {
        ServerPlayer player = state.player;
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z).lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : new Vec3(look.x, 0, look.z).normalize();
        double angle = Math.toRadians(part.number(1, 90));
        Vec3 now = state.direction;
        state.direction = switch (part.word(0, "見ている方")) {
            case "上" -> new Vec3(0, 1, 0);
            case "下" -> new Vec3(0, -1, 0);
            case "前" -> flat;
            case "うしろ" -> flat.scale(-1);
            case "右" -> new Vec3(-flat.z, 0, flat.x);
            case "左" -> new Vec3(flat.z, 0, -flat.x);
            case "ランダム" -> new Vec3(state.level.getRandom().nextGaussian(), state.level.getRandom().nextGaussian(),
                    state.level.getRandom().nextGaussian()).normalize();
            case "場所の方" -> state.point.subtract(player.getEyePosition()).normalize();
            case "自分の方" -> player.getEyePosition().subtract(state.point).normalize();
            case "近くのモブの方" -> {
                List<LivingEntity> mobs = mobsAround(state.level, player, player.position(), 32);
                yield mobs.stream().min(java.util.Comparator.comparingDouble(mob -> mob.distanceToSqr(player)))
                        .map(mob -> mob.position().add(0, mob.getBbHeight() / 2, 0).subtract(player.getEyePosition()).normalize())
                        .orElse(look);
            }
            // 横にまわす：上から見て右まわりに 角度ぶん
            case "横にまわす" -> new Vec3(now.x * Math.cos(angle) - now.z * Math.sin(angle), now.y,
                    now.x * Math.sin(angle) + now.z * Math.cos(angle)).normalize();
            // 縦にまわす：上へ 角度ぶん起こす
            case "縦にまわす" -> {
                Vec3 side = new Vec3(now.x, 0, now.z);
                double horizontal = side.length();
                double pitch = Math.atan2(now.y, horizontal) + angle;
                Vec3 heading = horizontal < 1e-6 ? flat : side.normalize();
                yield heading.scale(Math.cos(pitch)).add(0, Math.sin(pitch), 0).normalize();
            }
            default -> look;
        };
        if (state.direction.lengthSqr() < 1e-6) state.direction = look;
    }

    /** 「場所」：今の場所を変える */
    static void movePoint(Magic.Part part, State state) {
        ServerPlayer player = state.player;
        switch (part.word(0, "自分")) {
            case "自分" -> state.point = player.position();
            case "見ている所" -> state.point = SpellEffects.aimPoint(state.level, player, clamp(part.number(1, 64), 1, 128));
            // 前：今の向きへ 距離ぶん進む
            case "前" -> state.point = state.point.add(state.direction.scale(clamp(part.number(1, 3), -64, 64)));
            case "上" -> state.point = state.point.add(0, clamp(part.number(1, 3), -64, 64), 0);
            case "まわり" -> state.point = randomAround(state.level, state.point, radius(part.number(1, 5)));
            case "もどる" -> state.point = state.base;
            case "近くのモブ", "ランダムなモブ" -> {
                // さがす広さは 距離（書かなければ 24）。見つからなければ 見ている所
                List<LivingEntity> mobs = mobsAround(state.level, player, player.position(), clamp(part.number(1, 24), 1, MAX_RADIUS));
                LivingEntity target = null;
                if (!mobs.isEmpty()) {
                    target = part.word(0, "").equals("近くのモブ")
                            ? mobs.stream().min(java.util.Comparator.comparingDouble(mob -> mob.distanceToSqr(player))).get()
                            : mobs.get(state.level.getRandom().nextInt(mobs.size()));
                }
                state.point = target != null ? target.position() : SpellEffects.aimPoint(state.level, player, 64);
                state.base = state.point;
            }
            default -> { }
        }
        state.pointMoved = !part.word(0, "").equals("自分");
        // 「もどる」以外は、ここを もどる場所にする（まわり・上・前 は もどらない）
        if (part.word(0, "").equals("自分") || part.word(0, "").equals("見ている所")) state.base = state.point;
    }

    /** 止める：モブをその場から動けなくする（AI も止める） */
    static void freeze(ServerLevel level, ServerPlayer player, Vec3 at, double radius, int duration, int time) {
        Map<LivingEntity, Vec3> frozenAt = new HashMap<>();
        Map<Mob, Boolean> hadNoAi = new HashMap<>();
        later(time, () -> {
            for (LivingEntity mob : mobsAround(level, player, at, radius)) {
                if (BigSpells.chronoFrozen.contains(mob)) continue;
                BigSpells.chronoFrozen.add(mob);
                frozenAt.put(mob, mob.position());
                if (mob instanceof Mob thinking) {
                    hadNoAi.put(thinking, thinking.isNoAi());
                    thinking.setNoAi(true);
                }
            }
        });
        for (int tick = 1; tick < duration; tick++) {
            later(time + tick, () -> frozenAt.forEach((mob, spot) -> {
                if (!mob.isAlive()) return;
                mob.setDeltaMovement(Vec3.ZERO);
                mob.teleportTo(spot.x, spot.y, spot.z);
                mob.needsSync = true;
            }));
        }
        later(time + duration, () -> frozenAt.keySet().forEach(mob -> {
            BigSpells.chronoFrozen.remove(mob);
            if (mob instanceof Mob thinking) thinking.setNoAi(hadNoAi.getOrDefault(thinking, false));
        }));
    }

    /** 撃つ：from から、aim の向きへ飛ばす */
    static void shoot(ServerLevel level, ServerPlayer player, EntityType<?> type, int count, double speed, double spread, Vec3 eye, Vec3 aim) {
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, aim.y))));
        float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        for (int index = 0; index < count; index++) {
            // まんなかから、左右にじゅんばんに ずらす
            float yawOffset = count == 1 ? 0 : (float) (spread * ((double) index / (count - 1) * 2 - 1));
            Vec3 direction = Vec3.directionFromRotation(pitch, yaw + yawOffset);
            Entity entity = type.create(level, EntitySpawnReason.TRIGGERED);
            if (entity == null) return;
            entity.snapTo(eye.x + direction.x, eye.y - 0.1 + direction.y, eye.z + direction.z, player.getYRot(), player.getXRot());
            if (entity instanceof Projectile projectile) projectile.setOwner(player);
            entity.setDeltaMovement(direction.scale(speed));
            level.addFreshEntity(entity);
        }
    }

    /** 見ている所などへ瞬間移動（ブロックにうまるときは、少し上にする） */
    static void teleportTo(ServerLevel level, ServerPlayer player, Vec3 target) {
        for (int lift = 0; lift < 6; lift++) {
            AABB box = player.getBoundingBox().move(target.subtract(player.position()));
            if (level.noCollision(player, box)) break;
            target = target.add(0, 0.5, 0);
        }
        playSound(level, player.position(), net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.0f);
        player.teleportTo(target.x, target.y, target.z);
        player.resetFallDistance();
        playSound(level, target, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.2f);
    }

    /** 平らな輪を描く */
    static void ring(ServerLevel level, ParticleOptions particle, Vec3 center, double radius, double turn) {
        int count = (int) Math.max(12, Math.min(240, radius * 12));
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2 * index / count + turn;
            level.sendParticles(particle, center.x + Math.cos(angle) * radius, center.y, center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
    }

    /** 粒の名前から、粒を作る（設定がいる粒は使えない） */
    static ParticleOptions particle(ServerPlayer player, Magic.Part part) {
        Optional<ParticleType<?>> type = BuiltInRegistries.PARTICLE_TYPE.getOptional(id(PARTICLE_NAMES, part.word(0, "")));
        if (type.isPresent() && type.get() instanceof ParticleOptions simple) return simple;
        unknown(player, part, "粒");
        return null;
    }

    /** 半径（radius ブロック）の中の、プレイヤー（半径 0 なら自分だけ） */
    static List<Player> playersAround(ServerLevel level, ServerPlayer player, Vec3 center, double radius) {
        if (radius <= 0) return List.of(player);
        return level.getEntitiesOfClass(Player.class, new AABB(center, center).inflate(radius), Player::isAlive);
    }

    /** center のまわり（radius ブロックの円の中）の、ランダムな場所 */
    static Vec3 randomAround(ServerLevel level, Vec3 center, double radius) {
        if (radius <= 0) return center;
        double angle = level.getRandom().nextDouble() * Math.PI * 2;
        double distance = Math.sqrt(level.getRandom().nextDouble()) * radius;
        return center.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
    }

    /** 日本語の名前を、マイクラの id にする（英語の id で書いてもよい） */
    static Identifier id(Map<String, String> names, String word) {
        String name = names.getOrDefault(word, word);
        Identifier result = Identifier.tryParse(name.toLowerCase());
        return result == null ? Identifier.withDefaultNamespace("air") : result;
    }

    /** 知らない名前をチャットで教える */
    static void unknown(ServerPlayer player, Magic.Part part, String kind) {
        player.sendSystemMessage(Component.literal("§cこえまほう：" + part.lineNumber() + "行目の" + kind + "「" + part.word(0, "") + "」がわかりません"));
    }

    /** 秒を tick にする */
    static int ticks(double seconds) {
        return (int) Math.round(clamp(seconds, 0, 60) * 20);
    }

    /** 半径を 0〜48 にする */
    static double radius(double value) {
        return clamp(value, 0, MAX_RADIUS);
    }

    /** value を low〜high にする */
    static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
