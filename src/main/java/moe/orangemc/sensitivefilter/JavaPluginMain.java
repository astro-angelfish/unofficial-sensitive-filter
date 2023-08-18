package moe.orangemc.sensitivefilter;

import com.google.gson.Gson;
import com.google.zxing.Binarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import moe.orangemc.sensitivefilter.config.PluginConfig;
import net.mamoe.mirai.console.plugin.jvm.JavaPlugin;
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescriptionBuilder;
import net.mamoe.mirai.contact.Member;
import net.mamoe.mirai.contact.NormalMember;
import net.mamoe.mirai.event.Event;
import net.mamoe.mirai.event.EventChannel;
import net.mamoe.mirai.event.GlobalEventChannel;
import net.mamoe.mirai.event.events.GroupMessageEvent;
import net.mamoe.mirai.message.data.Image;
import net.mamoe.mirai.message.data.MessageSource;
import net.mamoe.mirai.message.data.SingleMessage;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.commons.io.IOUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

public final class JavaPluginMain extends JavaPlugin {
    public static final JavaPluginMain INSTANCE = new JavaPluginMain();
    private PluginConfig config;
    private final Gson gson = new Gson();
    private final Tesseract tesseract = new Tesseract();
    private final QRCodeReader reader = new QRCodeReader();

    private JavaPluginMain() {
        super(new JvmPluginDescriptionBuilder("moe.orangemc.sensitivefilter", "0.1.0")
                .info("EG")
                .build());
    }

    @Override
    public void onEnable() {
        if (!getConfigFolder().exists()) {
            getConfigFolder().mkdirs();
        }
        File configFile = new File(getConfigFolder(), "config.json");
        if (!configFile.exists()) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("config.json")) {
                configFile.createNewFile();
                try (FileOutputStream fos = new FileOutputStream(configFile)) {
                    IOUtils.copy(is, fos);
                }
            } catch (Exception e) {
                getLogger().error("读取默认配置文件失败", e);
            }
        }

        try (FileReader fr = new FileReader(configFile)) {
            this.config = gson.fromJson(fr, PluginConfig.class);
        } catch (Exception e) {
            getLogger().error("读取配置文件失败", e);
        }

        EventChannel<Event> eventChannel = GlobalEventChannel.INSTANCE.parentScope(this);
        eventChannel.subscribeAlways(GroupMessageEvent.class, g -> {
            if (!config.monitorGroups.contains(g.getGroup().getId())) {
                return;
            }

            Member sender = g.getSender();
            if (sender.getPermission().getLevel() > 0) {
                return;
            }

            String message = g.getMessage().contentToString();
            // Avoid identity number leakage
            if (message.matches("\\d{17,17}[\\dX]")) {
                if (g.getPermission().getLevel() > 0) {
                    MessageSource.recall(g.getMessage());
                }
            }

            if (isSensitive(message)) {
                if (g.getPermission().getLevel() > 0) {
                    MessageSource.recall(g.getMessage());
                    if (sender instanceof NormalMember) {
                        NormalMember normalMember = (NormalMember) sender;
                        normalMember.kick("疑似诈骗鸡, 若有疑问请重新加群或联系bot开发者");
                    }
                } else {
                    g.getGroup().sendMessage("你看起来像是诈骗鸡, 请证明消息真实性");
                }
            }

            // Check image message
            if (g.getMessage().contains(Image.Key)) {
                boolean shouldBan = false;

                for (SingleMessage sm : g.getMessage()) {
                    if (sm instanceof Image) {
                        Image im = (Image) sm;
                        String format = im.getImageType().getFormatName();
                        File imageFile = new File(getDataFolder(), im.getImageId() + "." + format);
                        try {
                            imageFile.createNewFile();
                            URL url = new URL(Image.queryUrl(im));
                            URLConnection urlConnection = url.openConnection();
                            urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
                            try (InputStream is = urlConnection.getInputStream()) {
                                try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                                    IOUtils.copy(is, fos);
                                }
                            }
                        } catch (Exception e) {
                            getLogger().error("下载图片失败", e);
                        }

                        // First, check if the image contains that string
                        try {
                            String content = tesseract.doOCR(imageFile);
                            if (isSensitive(content)) {
                                shouldBan = true;
                                break;
                            }

                            // Avoid identity number leakage
                            if (message.matches("\\d{17,17}[\\dX]")) {
                                if (g.getPermission().getLevel() > 0) {
                                    MessageSource.recall(g.getMessage());
                                }
                            }
                        } catch (TesseractException e) {
                            getLogger().error("识图失败", e);
                        }

                        // Second, find the QR code, consider all the QR code is evil since there's somebody doesn't know how to check if it is safe.
                        try {
                            BufferedImage bi = ImageIO.read(imageFile);
                            int[] pixels = new int[bi.getWidth() * bi.getHeight()];
                            bi.getData().getPixels(0, 0, bi.getWidth(), bi.getHeight(), pixels);
                            Binarizer binarizer = new HybridBinarizer(new RGBLuminanceSource(bi.getWidth(), bi.getHeight(), pixels));
                            try {
                                reader.decode(new BinaryBitmap(binarizer));
                                shouldBan = true;
                                break;
                            } catch (NotFoundException e) {
                                // Nothing to do
                            }
                        } catch (Exception e) {
                            getLogger().error("读图失败", e);
                        }
                    }
                }

                if (g.getPermission().getLevel() > 0 && shouldBan) {
                    MessageSource.recall(g.getMessage());
                    if (sender instanceof NormalMember) {
                        NormalMember normalMember = (NormalMember) sender;
                        normalMember.kick("疑似诈骗鸡, 若有疑问请重新加群或联系bot开发者");
                    }
                } else if (shouldBan) {
                    g.getGroup().sendMessage("你看起来像是诈骗鸡, 请证明消息真实性");
                }
            }
        });
    }

    private boolean isSensitive(String content) {
        return content.matches("\\d{7,12}") || content.matches("通知") || content.matches("新生");
    }
}
