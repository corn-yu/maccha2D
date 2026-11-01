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
import net.minecraft.world.phys.AABB;
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

        State(ServerLevel level, ServerPlayer player) {
            this.level = level;
            this.player = player;
            this.point = player.position();
            this.base = point;
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
                // 数字があれば、見ている方へその距離。なければ「場所」で決めた所へ
                boolean toPoint = part.args().isEmpty() && state.pointMoved;
                double distance = clamp(part.number(0, 10), 1, 64);
                later(time, () -> {
                    if (toPoint) {
                        teleportTo(level, player, at);
                    } else {
                        SpellEffects.castWarp(level, player, distance);
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
                later(time, () -> shoot(level, player, type.get(), count, speed, spread));
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

    /** 「場所」：今の場所を変える */
    static void movePoint(Magic.Part part, State state) {
        ServerPlayer player = state.player;
        switch (part.word(0, "自分")) {
            case "自分" -> state.point = player.position();
            case "見ている所" -> state.point = SpellEffects.aimPoint(state.level, player, clamp(part.number(1, 64), 1, 128));
            case "前" -> {
                Vec3 look = player.getLookAngle();
                state.point = state.point.add(new Vec3(look.x, 0, look.z).normalize().scale(clamp(part.number(1, 3), -64, 64)));
            }
            case "上" -> state.point = state.point.add(0, clamp(part.number(1, 3), -64, 64), 0);
            case "まわり" -> state.point = randomAround(state.level, state.point, radius(part.number(1, 5)));
            case "もどる" -> state.point = state.base;
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

    /** 撃つ：目の前から、見ている方へ飛ばす */
    static void shoot(ServerLevel level, ServerPlayer player, EntityType<?> type, int count, double speed, double spread) {
        Vec3 eye = player.getEyePosition();
        for (int index = 0; index < count; index++) {
            // まんなかから、左右にじゅんばんに ずらす
            float yawOffset = count == 1 ? 0 : (float) (spread * ((double) index / (count - 1) * 2 - 1));
            Vec3 direction = Vec3.directionFromRotation(player.getXRot(), player.getYRot() + yawOffset);
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
