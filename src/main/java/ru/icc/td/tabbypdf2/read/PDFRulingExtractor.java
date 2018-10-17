package ru.icc.td.tabbypdf2.read;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.rendering.ImageType;

import ru.icc.td.tabbypdf2.model.Page;
import ru.icc.td.tabbypdf2.model.Ruling;
import ru.icc.td.tabbypdf2.util.Utils;

import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.List;

public class PDFRulingExtractor {

    private static final int GRAYSCALE_INTENSITY_THRESHOLD = 25;
    private static final int HORIZONTAL_EDGE_WIDTH_MINIMUM = 50;
    private static final int VERTICAL_EDGE_HEIGHT_MINIMUM = 10;
    private static final float POINT_SNAP_DISTANCE_THRESHOLD = 8f;

    private final PDDocument pdDocument;
    private final List<Ruling> visibleRulings;

    private void release() {
        visibleRulings.clear();
    }

    public PDFRulingExtractor(PDDocument pdDocument) {
        this.pdDocument = pdDocument;
        visibleRulings = new ArrayList<>(200);
    }

    public void readTo(int pageIndex, Page page) {
        page.addVisibleRulings(detect(page));

    }

    public List<Ruling> detect(Page page) {

        // get horizontal & vertical lines
        // we get these from an image of the PDF and not the PDF itself because sometimes there are invisible PDF
        // instructions that are interpreted incorrectly as visible elements - we really want to capture what a
        // person sees when they look at the PDF
        BufferedImage image;
        PDPage pdfPage = pdDocument.getPage(page.getIndex());

        try {
            image = Utils.pageConvertToImage(pdfPage, 144, ImageType.GRAY);
        } catch (IOException e) {
            return new ArrayList<>();
        }

        List<Ruling> horizontalRulings = this.getHorizontalRulings(image);

        // now check the page for vertical lines, but remove the text first to make things less confusing
        PDDocument removeTextDocument = null;
        try {
            removeTextDocument = this.removeText(pdfPage);
            image = Utils.pageConvertToImage(pdfPage, 144, ImageType.GRAY);
        } catch (Exception e) {
            return new ArrayList<>();
        } finally {
            if (removeTextDocument != null) {
                try {
                    removeTextDocument.close();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }

        List<Ruling> verticalRulings = this.getVerticalRulings(image);

        List<Ruling> allEdges = new ArrayList<>(horizontalRulings);
        allEdges.addAll(verticalRulings);

        List<Rectangle> tableAreas = new ArrayList<>();

        // if we found some edges, try to find some tables based on them
        if (allEdges.size() > 0) {
            // now we need to snap edge endpoints to a grid
            Utils.snapPoints(allEdges, POINT_SNAP_DISTANCE_THRESHOLD, POINT_SNAP_DISTANCE_THRESHOLD);

            // normalize the rulings to make sure snapping didn't create any wacky non-horizontal/vertical rulings
            for (List<Ruling> rulings : Arrays.asList(horizontalRulings, verticalRulings)) {
                for (Iterator<Ruling> iterator = rulings.iterator(); iterator.hasNext(); ) {
                    Ruling ruling = iterator.next();

                    ruling.normalize();
                    if (ruling.oblique()) {
                        iterator.remove();
                    }
                }
            }

            horizontalRulings = Ruling.collapseOrientedRulings(horizontalRulings, 5);
            verticalRulings = Ruling.collapseOrientedRulings(verticalRulings, 5);
        }

        List<Ruling> allRulings = new ArrayList<>();

        float pageHeight = (float) Math.abs(page.getHeight());

        for (Line2D.Float ruling : horizontalRulings) {
            ruling.x1 = ruling.x1 / 2;
            ruling.y1 = pageHeight - ruling.y1 / 2;
            ruling.x2 = ruling.x2 / 2;
            ruling.y2 = pageHeight - ruling.y2 / 2;
            allRulings.add(new Ruling(ruling.getP1(), ruling.getP2(), page));
        }

        for (Line2D.Float ruling : verticalRulings) {
            ruling.x1 = ruling.x1 / 2;
            ruling.y1 = pageHeight - ruling.y1 / 2;
            ruling.x2 = ruling.x2 / 2;
            ruling.y2 = pageHeight - ruling.y2 / 2;
            allRulings.add((Ruling) ruling);
        }

        //List<Ruling> rulings = new ArrayList<>();
        return allRulings;
    }

    private List<Ruling> getHorizontalRulings(BufferedImage image) {

        // get all horizontal edges, which we'll define as a change in grayscale colour
        // along a straight line of a certain length
        ArrayList<Ruling> horizontalRulings = new ArrayList<>();

        Raster r = image.getRaster();
        int width = r.getWidth();
        int height = r.getHeight();

        for (int x = 0; x < width; x++) {

            int[] lastPixel = r.getPixel(x, 0, (int[]) null);

            for (int y = 1; y < height - 1; y++) {

                int[] currPixel = r.getPixel(x, y, (int[]) null);

                int diff = Math.abs(currPixel[0] - lastPixel[0]);
                if (diff > GRAYSCALE_INTENSITY_THRESHOLD) {
                    // we hit what could be a line
                    // don't bother scanning it if we've hit a pixel in the line before
                    boolean alreadyChecked = false;
                    for (Line2D.Float line : horizontalRulings) {
                        if (y == line.getY1() && x >= line.getX1() && x <= line.getX2()) {
                            alreadyChecked = true;
                            break;
                        }
                    }

                    if (alreadyChecked) {
                        lastPixel = currPixel;
                        continue;
                    }

                    int lineX = x + 1;

                    while (lineX < width) {
                        int[] linePixel = r.getPixel(lineX, y, (int[]) null);
                        int[] abovePixel = r.getPixel(lineX, y - 1, (int[]) null);

                        if (Math.abs(linePixel[0] - abovePixel[0]) <= GRAYSCALE_INTENSITY_THRESHOLD
                                || Math.abs(currPixel[0] - linePixel[0]) > GRAYSCALE_INTENSITY_THRESHOLD) {
                            break;
                        }

                        lineX++;
                    }

                    int endX = lineX - 1;
                    int lineWidth = endX - x;
                    if (lineWidth > HORIZONTAL_EDGE_WIDTH_MINIMUM) {
                        horizontalRulings.add(new Ruling(new Point2D.Float(x, y), new Point2D.Float(endX, y)));
                    }
                }

                lastPixel = currPixel;
            }
        }

        return horizontalRulings;
    }

    private List<Ruling> getVerticalRulings(BufferedImage image) {

        // get all vertical edges, which we'll define as a change in grayscale colour
        // along a straight line of a certain length
        ArrayList<Ruling> verticalRulings = new ArrayList<>();

        Raster r = image.getRaster();
        int width = r.getWidth();
        int height = r.getHeight();

        for (int y = 0; y < height; y++) {

            int[] lastPixel = r.getPixel(0, y, (int[]) null);

            for (int x = 1; x < width - 1; x++) {

                int[] currPixel = r.getPixel(x, y, (int[]) null);

                int diff = Math.abs(currPixel[0] - lastPixel[0]);
                if (diff > GRAYSCALE_INTENSITY_THRESHOLD) {
                    // we hit what could be a line
                    // don't bother scanning it if we've hit a pixel in the line before
                    boolean alreadyChecked = false;
                    for (Line2D.Float line : verticalRulings) {
                        if (x == line.getX1() && y >= line.getY1() && y <= line.getY2()) {
                            alreadyChecked = true;
                            break;
                        }
                    }

                    if (alreadyChecked) {
                        lastPixel = currPixel;
                        continue;
                    }

                    int lineY = y + 1;

                    while (lineY < height) {
                        int[] linePixel = r.getPixel(x, lineY, (int[]) null);
                        int[] leftPixel = r.getPixel(x - 1, lineY, (int[]) null);

                        if (Math.abs(linePixel[0] - leftPixel[0]) <= GRAYSCALE_INTENSITY_THRESHOLD
                                || Math.abs(currPixel[0] - linePixel[0]) > GRAYSCALE_INTENSITY_THRESHOLD) {
                            break;
                        }

                        lineY++;
                    }

                    int endY = lineY - 1;
                    int lineLength = endY - y;
                    if (lineLength > VERTICAL_EDGE_HEIGHT_MINIMUM) {
                        verticalRulings.add(new Ruling(new Point2D.Float(x, y), new Point2D.Float(x, endY)));
                    }
                }

                lastPixel = currPixel;
            }
        }

        return verticalRulings;
    }


    // taken from http://www.docjar.com/html/api/org/apache/pdfbox/examples/util/RemoveAllText.java.html
    private PDDocument removeText(PDPage page) throws IOException {

        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();
        List<Object> tokens = parser.getTokens();
        List<Object> newTokens = new ArrayList<>();
        for (Object token : tokens) {
            if (token instanceof Operator) {
                Operator op = (Operator) token;
                if (op.getName().equals("TJ") || op.getName().equals("Tj")) {
                    //remove the one argument to this operator
                    newTokens.remove(newTokens.size() - 1);
                    continue;
                }
            }
            newTokens.add(token);
        }

        PDDocument document = new PDDocument();
        document.addPage(page);

        PDStream newContents = new PDStream(document);
        OutputStream out = newContents.createOutputStream(COSName.FLATE_DECODE);
        ContentStreamWriter writer = new ContentStreamWriter(out);
        writer.writeTokens(newTokens);
        out.close();
        page.setContents(newContents);

        return document;

    }
}
