import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class Tune {
 
    static final int MAX_WIDTH = 720;
    static final double THRESHOLD = 0.8;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: java Tune <截图路径> [x y]");
            return;
        }
        BufferedImage src = ImageIO.read(new File(args[0]));
        if (src == null) {
            System.out.println("读不出这张图: " + args[0]);
            return;
        }
        System.out.printf("原图 %dx%d%n", src.getWidth(), src.getHeight());

        // 缩放到 MAX_WIDTH，和 ImageRecognizer 的第一步一致
        double scale = src.getWidth() > MAX_WIDTH ? (double) MAX_WIDTH / src.getWidth() : 1.0;
        int w = Math.max(1, (int) (src.getWidth() * scale));
        int h = Math.max(1, (int) (src.getHeight() * scale));
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        double[] img = gray(small);
        System.out.printf("缩放后 %dx%d (scale=%.4f)%n", w, h, scale);

        int tx = -1, ty = -1;
        if (args.length >= 3) {
            tx = (int) Math.round(Integer.parseInt(args[1]) * scale);
            ty = (int) Math.round(Integer.parseInt(args[2]) * scale);
            System.out.printf("已知 × 原图(%s,%s) → 缩略图(%d,%d)%n", args[1], args[2], tx, ty);
        }

        double[] s1 = integral(img, w, h);
        double[] s2 = integralSq(img, w, h);

        System.out.println();
        System.out.printf("%-28s %8s %-18s %8s %-18s%n",
                "模板", "全图最高分", "位置", "×处得分", "×处最接近的模板");

        for (Variant v : variants()) {
            for (int size : v.sizes) {
                double[] t = v.build(size);
                double[] tmplStat = zeroMean(t);
                Result r = match(img, w, h, s1, s2, tmplStat, size);
                double atTarget = tx >= 0 ? scoreAt(img, w, h, tmplStat, size, tx, ty) : Double.NaN;
                System.out.printf("%-28s %8.3f (%4d,%4d)      %8.3f%n",
                        v.name + "/" + size, r.best, r.bestX, r.bestY,
                        Double.isNaN(atTarget) ? 0.0 : atTarget);
            }
        }
    }

    /** 一个模板族：同一套画法，跑多个尺寸。 */
    record Variant(String name, int[] sizes, double strokeFrac, double insetFrac,
                   double circleFrac, int circleGray, String text, String font) {

        double[] build(int size) {
            BufferedImage bmp = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bmp.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(128, 128, 128));
            g.fillRect(0, 0, size, size);
            if (text != null) {
                Font f = new Font(font, Font.BOLD, size);
                g.setFont(f);
                // 把文字收进框宽的 90%，再按实测宽度回调一次字号
                double w = g.getFontMetrics().stringWidth(text);
                double target = size * 0.90;
                if (w > target) {
                    f = f.deriveFont((float) (size * target / w));
                    g.setFont(f);
                }
                FontMetrics fm = g.getFontMetrics();
                int baseline = size / 2 - (fm.getAscent() + fm.getDescent()) / 2 + fm.getAscent();
                g.setColor(Color.WHITE);
                g.drawString(text, Math.round((size - fm.stringWidth(text)) / 2f), baseline);
            } else {
                if (circleFrac > 0) {
                    int d = Math.max(2, (int) Math.round(size * circleFrac));
                    g.setColor(new Color(circleGray, circleGray, circleGray));
                    g.fillOval((size - d) / 2, (size - d) / 2, d, d);
                }
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke((float) (size * strokeFrac),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int inset = Math.max(0, (int) Math.round(size * insetFrac));
                g.drawLine(inset, inset, size - 1 - inset, size - 1 - inset);
                g.drawLine(size - 1 - inset, inset, inset, size - 1 - inset);
            }
            g.dispose();
            return gray(bmp);
        }
    }

    static List<Variant> variants() {
        int[] dense = {12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 34, 38, 42, 46, 50};
        int[] textSizes = {22, 26, 30, 36, 42, 50, 60};
        List<Variant> list = new ArrayList<>();
        // 现行生产配置，放在第一行：换截图复跑时先看它，确认没被改坏
        list.add(new Variant("生产 inset.12 密", dense, 0.125, 0.12, 0, 0, null, null));
        // 文字模板：直接验证"图像层能不能认「跳过」"。中文字体本机只有 Windows 的，
        // 和安卓上的 Noto Sans CJK / MiSans 不是一套，所以这里同时试几种，看分数对字体敏不敏感。
        list.add(new Variant("跳过 雅黑", textSizes, 0, 0, 0, 0, "跳过", "Microsoft YaHei"));
        list.add(new Variant("跳过 黑体", textSizes, 0, 0, 0, 0, "跳过", "SimHei"));
        list.add(new Variant("跳过 宋体", textSizes, 0, 0, 0, 0, "跳过", "SimSun"));
        return list;
    }

    // ---- 以下与评分有关的部分，刻意和 OpenCV 的 TM_CCOEFF_NORMED 对齐 ----

    static double[] zeroMean(double[] t) {
        double sum = 0;
        for (double v : t) sum += v;
        double mean = sum / t.length;
        double[] out = new double[t.length + 1];
        double sq = 0;
        for (int i = 0; i < t.length; i++) {
            out[i] = t[i] - mean;
            sq += out[i] * out[i];
        }
        out[t.length] = sq;
        return out;
    }

    record Result(double best, int bestX, int bestY) {}

    static Result match(double[] img, int w, int h, double[] s1, double[] s2,
                        double[] t, int size) {
        int tw = size, th = size;
        double sumT2 = t[t.length - 1];
        double best = -1;
        int bx = 0, by = 0;
        int steps = 1;
        for (int y = 0; y + th <= h; y += steps) {
            for (int x = 0; x + tw <= w; x += steps) {
                double cross = 0;
                for (int j = 0; j < th; j++) {
                    int row = (y + j) * w + x;
                    int trow = j * tw;
                    for (int i = 0; i < tw; i++) {
                        cross += t[trow + i] * img[row + i];
                    }
                }
                double varI = windowVar(s1, s2, w, x, y, tw, th);
                if (varI <= 1e-6) continue;
                double score = cross / Math.sqrt(sumT2 * varI);
                if (score > best) {
                    best = score;
                    bx = x;
                    by = y;
                }
            }
        }
        return new Result(best, bx + tw / 2, by + th / 2);
    }

    /** 精确算某个点（作为模板中心）处的分数。 */
    static double scoreAt(double[] img, int w, int h, double[] t, int size, int cx, int cy) {
        int tw = size, th = size;
        int x = cx - tw / 2, y = cy - th / 2;
        if (x < 0 || y < 0 || x + tw > w || y + th > h) return Double.NaN;
        double cross = 0;
        for (int j = 0; j < th; j++) {
            int row = (y + j) * w + x;
            int trow = j * tw;
            for (int i = 0; i < tw; i++) cross += t[trow + i] * img[row + i];
        }
        double sum = 0, sq = 0;
        for (int j = 0; j < th; j++) {
            int row = (y + j) * w + x;
            for (int i = 0; i < tw; i++) {
                double v = img[row + i];
                sum += v;
                sq += v * v;
            }
        }
        double n = tw * th;
        double varI = sq - sum * sum / n;
        if (varI <= 1e-6) return Double.NaN;
        return cross / Math.sqrt(t[t.length - 1] * varI);
    }

    static double windowVar(double[] s1, double[] s2, int w, int x, int y, int tw, int th) {
        double sum = area(s1, w, x, y, tw, th);
        double sq = area(s2, w, x, y, tw, th);
        double n = tw * th;
        return sq - sum * sum / n;
    }

    static double area(double[] s, int w, int x, int y, int tw, int th) {
        int stride = w + 1;
        int x1 = x, y1 = y, x2 = x + tw, y2 = y + th;
        return s[y2 * stride + x2] - s[y1 * stride + x2] - s[y2 * stride + x1] + s[y1 * stride + x1];
    }

    static double[] integral(double[] v, int w, int h) {
        int stride = w + 1;
        double[] s = new double[stride * (h + 1)];
        for (int y = 0; y < h; y++) {
            double rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += v[y * w + x];
                s[(y + 1) * stride + (x + 1)] = s[y * stride + (x + 1)] + rowSum;
            }
        }
        return s;
    }

    static double[] integralSq(double[] v, int w, int h) {
        double[] sq = new double[v.length];
        for (int i = 0; i < v.length; i++) sq[i] = v[i] * v[i];
        return integral(sq, w, h);
    }

    static double[] gray(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        double[] out = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                // 与 OpenCV COLOR_RGBA2GRAY 同一组系数
                out[y * w + x] = 0.299 * r + 0.587 * gg + 0.114 * b;
            }
        }
        return out;
    }
}
