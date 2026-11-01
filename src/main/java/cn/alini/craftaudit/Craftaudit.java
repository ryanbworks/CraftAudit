package cn.alini.craftaudit;

import cn.alini.craftaudit.config.Config;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.util.Lang;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import java.nio.file.Path;


@Mod(Craftaudit.MODID)
public class Craftaudit {

    public static final String MODID = "craftaudit";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Craftaudit() {
        LOGGER.info("模组正在初始化");
        // 注册到Forge事件总线
        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        LOGGER.info("模组初始化完成");
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerStartingEvent event) {
        LOGGER.info("模组数据库正在初始化");
        try{
            Path dbPath = FMLPaths.GAMEDIR.get().resolve("craftaudit.db");
            Database.init(dbPath);
            LOGGER.info("模组数据库初始化完成，路径: " + dbPath);
        } catch (Exception e) {
            // 禁用模组功能：不再抛出异常导致服务器崩溃，未连接时日志写入会被跳过
            LOGGER.error("模组数据库初始化失败，模组功能已禁用（不会记录任何审计日志！）", e);
        }
        Lang.reload();
    }

}
