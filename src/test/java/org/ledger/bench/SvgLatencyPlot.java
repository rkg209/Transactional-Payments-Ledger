package org.ledger.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Hand-rolled SVG string builder for the committed latency plot -- no plotting library, no
 * Python/JS toolchain in the repo. x = contention level (categorical), y = p99 latency; one
 * polyline per (strategy, isolation) series; the read_committed crossover, if any, is marked with a
 * vertical rule.
 */
final class SvgLatencyPlot {

  private static final int WIDTH = 760;
  private static final int HEIGHT = 460;
  private static final int MARGIN_LEFT = 70;
  private static final int MARGIN_RIGHT = 190;
  private static final int MARGIN_TOP = 34;
  private static final int MARGIN_BOTTOM = 56;

  private static final Map<String, String> SERIES_COLORS =
      Map.of(
          "optimistic:read_committed", "#2563eb",
          "optimistic:serializable", "#93c5fd",
          "pessimistic:read_committed", "#dc2626",
          "pessimistic:serializable", "#fca5a5");

  static String render(List<BenchmarkReport.Cell> cells, Integer crossoverReadCommitted) {
    if (cells.isEmpty()) {
      return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"100\">"
          + "<text x=\"10\" y=\"50\" font-family=\"sans-serif\">No data</text></svg>\n";
    }

    TreeSet<Integer> contentionLevels = new TreeSet<>();
    for (BenchmarkReport.Cell c : cells) {
      contentionLevels.add(c.contention());
    }
    List<Integer> levels = List.copyOf(contentionLevels);
    int n = levels.size();

    Map<String, List<BenchmarkReport.Cell>> series = new LinkedHashMap<>();
    for (BenchmarkReport.Cell c : cells) {
      series.computeIfAbsent(c.strategy() + ":" + c.isolation(), k -> new ArrayList<>()).add(c);
    }
    for (List<BenchmarkReport.Cell> s : series.values()) {
      s.sort(Comparator.comparingInt(BenchmarkReport.Cell::contention));
    }

    long maxP99Millis = 1;
    for (BenchmarkReport.Cell c : cells) {
      maxP99Millis = Math.max(maxP99Millis, c.p99Millis());
    }

    int plotWidth = WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
    int plotHeight = HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;

    StringBuilder svg = new StringBuilder();
    svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
        .append(WIDTH)
        .append("\" height=\"")
        .append(HEIGHT)
        .append("\" viewBox=\"0 0 ")
        .append(WIDTH)
        .append(' ')
        .append(HEIGHT)
        .append("\">\n");
    svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
    svg.append(
        "<text x=\""
            + (WIDTH / 2)
            + "\" y=\"20\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\""
            + " font-weight=\"bold\">p99 transfer latency by contention (SPEC 0008)</text>\n");

    svg.append(axisLine(MARGIN_LEFT, MARGIN_TOP, MARGIN_LEFT, MARGIN_TOP + plotHeight));
    svg.append(
        axisLine(
            MARGIN_LEFT,
            MARGIN_TOP + plotHeight,
            MARGIN_LEFT + plotWidth,
            MARGIN_TOP + plotHeight));

    for (int i = 0; i <= 4; i++) {
      double frac = i / 4.0;
      int y = MARGIN_TOP + plotHeight - (int) Math.round(frac * plotHeight);
      long value = Math.round(frac * maxP99Millis);
      svg.append(
          String.format(
              "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#eee\"/>\n",
              MARGIN_LEFT, y, MARGIN_LEFT + plotWidth, y));
      svg.append(
          String.format(
              "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"10\""
                  + " text-anchor=\"end\">%d ms</text>\n",
              MARGIN_LEFT - 6, y + 3, value));
    }

    for (int i = 0; i < n; i++) {
      int x = xFor(i, n, plotWidth);
      svg.append(
          String.format(
              "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"10\""
                  + " text-anchor=\"middle\">%d</text>\n",
              x, MARGIN_TOP + plotHeight + 16, levels.get(i)));
    }
    svg.append(
        String.format(
            "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\""
                + " text-anchor=\"middle\">threads per hot account</text>\n",
            MARGIN_LEFT + plotWidth / 2, HEIGHT - 10));

    int legendY = MARGIN_TOP;
    for (Map.Entry<String, List<BenchmarkReport.Cell>> entry : series.entrySet()) {
      String key = entry.getKey();
      String color = SERIES_COLORS.getOrDefault(key, "#000000");
      StringBuilder points = new StringBuilder();
      for (BenchmarkReport.Cell c : entry.getValue()) {
        int idx = levels.indexOf(c.contention());
        int x = xFor(idx, n, plotWidth);
        int y = yFor(c.p99Millis(), maxP99Millis, plotHeight);
        points.append(x).append(',').append(y).append(' ');
      }
      svg.append(
          String.format(
              "<polyline fill=\"none\" stroke=\"%s\" stroke-width=\"2\" points=\"%s\"/>\n",
              color, points.toString().trim()));
      for (BenchmarkReport.Cell c : entry.getValue()) {
        int idx = levels.indexOf(c.contention());
        int x = xFor(idx, n, plotWidth);
        int y = yFor(c.p99Millis(), maxP99Millis, plotHeight);
        svg.append(
            String.format("<circle cx=\"%d\" cy=\"%d\" r=\"3\" fill=\"%s\"/>\n", x, y, color));
      }
      svg.append(
          String.format(
              "<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"10\" fill=\"%s\"/>\n",
              MARGIN_LEFT + plotWidth + 20, legendY, color));
      svg.append(
          String.format(
              "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\">%s</text>\n",
              MARGIN_LEFT + plotWidth + 34, legendY + 9, key.replace(':', ' ')));
      legendY += 18;
    }

    if (crossoverReadCommitted != null && levels.contains(crossoverReadCommitted)) {
      int idx = levels.indexOf(crossoverReadCommitted);
      int x = xFor(idx, n, plotWidth);
      svg.append(
          String.format(
              "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#16a34a\""
                  + " stroke-dasharray=\"4\"/>\n",
              x, MARGIN_TOP, x, MARGIN_TOP + plotHeight));
      svg.append(
          String.format(
              "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"10\""
                  + " fill=\"#16a34a\">crossover=%d</text>\n",
              x + 4, MARGIN_TOP + 12, crossoverReadCommitted));
    }

    svg.append("</svg>\n");
    return svg.toString();
  }

  private static int xFor(int index, int n, int plotWidth) {
    return MARGIN_LEFT + (n == 1 ? plotWidth / 2 : index * plotWidth / (n - 1));
  }

  private static int yFor(long p99Millis, long maxP99Millis, int plotHeight) {
    return MARGIN_TOP
        + plotHeight
        - (int) Math.round((double) p99Millis / maxP99Millis * plotHeight);
  }

  private static String axisLine(int x1, int y1, int x2, int y2) {
    return String.format(
        "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#333\"/>\n", x1, y1, x2, y2);
  }

  private SvgLatencyPlot() {}
}
