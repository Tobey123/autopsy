/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011-2017 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.modules.embeddedfileextractor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.IllegalArgumentException;
import java.lang.IndexOutOfBoundsException;
import java.lang.NullPointerException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.apache.poi.POIXMLException;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.record.RecordInputStream.LeftoverDataException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.RecordFormatException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.services.FileManager;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.ingest.IngestJobContext;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.autopsy.ingest.ModuleContentEvent;
import org.sleuthkit.autopsy.modules.filetypeid.FileTypeDetector;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.EncodedFileOutputStream;
import org.sleuthkit.datamodel.ReadContentInputStream;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;

class ImageExtractor {

    private final FileManager fileManager;
    private final IngestServices services;
    private static final Logger logger = Logger.getLogger(ImageExtractor.class.getName());
    private final IngestJobContext context;
    private String parentFileName;
    private final String UNKNOWN_NAME_PREFIX = "image_"; //NON-NLS
    private final FileTypeDetector fileTypeDetector;

    private String moduleDirRelative;
    private String moduleDirAbsolute;

    /**
     * Enum of mimetypes which support image extraction
     */
    enum SupportedImageExtractionFormats {

        DOC("application/msword"), //NON-NLS
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"), //NON-NLS
        PPT("application/vnd.ms-powerpoint"), //NON-NLS
        PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation"), //NON-NLS
        XLS("application/vnd.ms-excel"), //NON-NLS
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); //NON-NLS

        private final String mimeType;

        SupportedImageExtractionFormats(final String mimeType) {
            this.mimeType = mimeType;
        }

        @Override
        public String toString() {
            return this.mimeType;
        }
        // TODO Expand to support more formats
    }
    private SupportedImageExtractionFormats abstractFileExtractionFormat;

    ImageExtractor(IngestJobContext context, FileTypeDetector fileTypeDetector, String moduleDirRelative, String moduleDirAbsolute) {

        this.fileManager = Case.getCurrentCase().getServices().getFileManager();
        this.services = IngestServices.getInstance();
        this.context = context;
        this.fileTypeDetector = fileTypeDetector;
        this.moduleDirRelative = moduleDirRelative;
        this.moduleDirAbsolute = moduleDirAbsolute;
    }

    /**
     * This method returns true if the file format is currently supported. Else
     * it returns false. Performs only Apache Tika based detection.
     *
     * @param abstractFile The AbstractFilw whose mimetype is to be determined.
     *
     * @return This method returns true if the file format is currently
     *         supported. Else it returns false.
     */
    boolean isImageExtractionSupported(AbstractFile abstractFile) {
        try {
            String abstractFileMimeType = fileTypeDetector.getFileType(abstractFile);
            for (SupportedImageExtractionFormats s : SupportedImageExtractionFormats.values()) {
                if (s.toString().equals(abstractFileMimeType)) {
                    abstractFileExtractionFormat = s;
                    return true;
                }
            }
            return false;
        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, "Error executing FileTypeDetector.getFileType()", ex); // NON-NLS
            return false;
        }
    }

    /**
     * This method selects the appropriate process of extracting images from
     * files using POI classes. Once the images have been extracted, the method
     * adds them to the DB and fires a ModuleContentEvent. ModuleContent Event
     * is not fired if the no images were extracted from the processed file.
     *
     * @param format
     * @param abstractFile The abstract file to be processed.
     */
    void extractImage(AbstractFile abstractFile) {
        // 
        // switchcase for different supported formats
        // process abstractFile according to the format by calling appropriate methods.

        List<ExtractedImage> listOfExtractedImages = null;
        List<AbstractFile> listOfExtractedImageAbstractFiles = null;
        this.parentFileName = EmbeddedFileExtractorIngestModule.getUniqueName(abstractFile);
        //check if already has derived files, skip
        try {
            if (abstractFile.hasChildren()) {
                //check if local unpacked dir exists
                if (new File(getOutputFolderPath(parentFileName)).exists()) {
                    logger.log(Level.INFO, "File already has been processed as it has children and local unpacked file, skipping: {0}", abstractFile.getName()); //NON-NLS
                    return;
                }
            }
        } catch (TskCoreException e) {
            logger.log(Level.SEVERE, String.format("Error checking if file already has been processed, skipping: %s", parentFileName), e); //NON-NLS
            return;
        }
        switch (abstractFileExtractionFormat) {
            case DOC:
                listOfExtractedImages = extractImagesFromDoc(abstractFile);
                break;
            case DOCX:
                listOfExtractedImages = extractImagesFromDocx(abstractFile);
                break;
            case PPT:
                listOfExtractedImages = extractImagesFromPpt(abstractFile);
                break;
            case PPTX:
                listOfExtractedImages = extractImagesFromPptx(abstractFile);
                break;
            case XLS:
                listOfExtractedImages = extractImagesFromXls(abstractFile);
                break;
            case XLSX:
                listOfExtractedImages = extractImagesFromXlsx(abstractFile);
                break;
            default:
                break;
        }

        if (listOfExtractedImages == null) {
            return;
        }
        // the common task of adding abstractFile to derivedfiles is performed.
        listOfExtractedImageAbstractFiles = new ArrayList<>();
        for (ExtractedImage extractedImage : listOfExtractedImages) {
            try {
                listOfExtractedImageAbstractFiles.add(fileManager.addDerivedFile(extractedImage.getFileName(), extractedImage.getLocalPath(), extractedImage.getSize(),
                        extractedImage.getCtime(), extractedImage.getCrtime(), extractedImage.getAtime(), extractedImage.getAtime(),
                        true, abstractFile, null, EmbeddedFileExtractorModuleFactory.getModuleName(), null, null, TskData.EncodingType.XOR1));
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, NbBundle.getMessage(this.getClass(), "EmbeddedFileExtractorIngestModule.ImageExtractor.extractImage.addToDB.exception.msg"), ex); //NON-NLS
            }
        }
        if (!listOfExtractedImages.isEmpty()) {
            services.fireModuleContentEvent(new ModuleContentEvent(abstractFile));
            context.addFilesToJob(listOfExtractedImageAbstractFiles);
        }
    }

    /**
     * Extract images from doc format files.
     *
     * @param af the file from which images are to be extracted.
     *
     * @return list of extracted images. Returns null in case no images were
     *         extracted.
     */
    private List<ExtractedImage> extractImagesFromDoc(AbstractFile af) {
        List<Picture> listOfAllPictures;
        
        try {
            HWPFDocument doc = new HWPFDocument(new ReadContentInputStream(af));
            PicturesTable pictureTable = doc.getPicturesTable();
            listOfAllPictures = pictureTable.getAllPictures();
        } catch (IOException | IllegalArgumentException |
                IndexOutOfBoundsException | NullPointerException ex) {
            // IOException:
            // Thrown when the document has issues being read.
            
            // IllegalArgumentException:
            // This will catch OldFileFormatException, which is thrown when the
            // document's format is Word 95 or older. Alternatively, this is
            // thrown when attempting to load an RTF file as a DOC file.
            // However, our code verifies the file format before ever running it
            // through the ImageExtractor. This exception gets thrown in the
            // "IN10-0137.E01" image regardless. The reason is unknown.
            
            // IndexOutOfBoundsException:
            // NullPointerException:
            // These get thrown in certain images. The reason is unknown. It is
            // likely due to problems with the file formats that POI is poorly
            // handling.
            
            return null;
        } catch (Throwable ex) {
            // instantiating POI containers throw RuntimeExceptions
            logger.log(Level.SEVERE, NbBundle.getMessage(this.getClass(), "EmbeddedFileExtractorIngestModule.ImageExtractor.docContainer.init.err", af.getName()), ex); //NON-NLS
            return null;
        }

        String outputFolderPath;
        if (listOfAllPictures.isEmpty()) {
            return null;
        } else {
            outputFolderPath = getOutputFolderPath(this.parentFileName);
        }
        if (outputFolderPath == null) {
            return null;
        }
        List<ExtractedImage> listOfExtractedImages = new ArrayList<>();
        byte[] data = null;
        for (Picture picture : listOfAllPictures) {
            String fileName = picture.suggestFullFileName();
            try {
                data = picture.getContent();
            } catch (Exception ex) {
                return null;
            }
            writeExtractedImage(Paths.get(outputFolderPath, fileName).toString(), data);
            // TODO Extract more info from the Picture viz ctime, crtime, atime, mtime
            listOfExtractedImages.add(new ExtractedImage(fileName, getFileRelativePath(fileName), picture.getSize(), af));
        }

        return listOfExtractedImages;
    }

    /**
     * Extract images from docx format files.
     *
     * @param af the file from which images are to be extracted.
     *
     * @return list of extracted images. Returns null in case no images were
     *         extracted.
     */
    private List<ExtractedImage> extractImagesFromDocx(AbstractFile af) {
        List<XWPFPictureData> listOfAllPictures = null;
        
        try {
            XWPFDocument docx = new XWPFDocument(new ReadContentInputStream(af));
            listOfAllPictures = docx.getAllPictures();
        } catch (POIXMLException | IOException ex) {
            // POIXMLException:
            // Thrown when document fails to load
            
            // IOException:
            // Thrown when the document has issues being read.
            
            return null;
        } catch (Throwable ex) {
            // instantiating POI containers throw RuntimeExceptions
            logger.log(Level.SEVERE, NbBundle.getMessage(this.getClass(), "EmbeddedFileExtractorIngestModule.ImageExtractor.docxContainer.init.err", af.getName()), ex); //NON-NLS
            return null;
        }

        // if no images are extracted from the PPT, return null, else initialize
        // the output folder for image extraction.
        String outputFolderPath;
        if (listOfAllPictures.isEmpty()) {
            return null;
        } else {
            outputFolderPath = getOutputFolderPath(this.parentFileName);
        }
        if (outputFolderPath == null) {
            return null;
        }
        List<ExtractedImage> listOfExtractedImages = new ArrayList<>();
        byte[] data = null;
        for (XWPFPictureData xwpfPicture : listOfAllPictures) {
            String fileName = xwpfPicture.getFileName();
            try {
                data = xwpfPicture.getData();
            } catch (Exception ex) {
                return null;
            }
            writeExtractedImage(Paths.get(outputFolderPath, fileName).toString(), data);
            listOfExtractedImages.add(new ExtractedImage(fileName, getFileRelativePath(fileName), xwpfPicture.getData().length, af));
        }
        return listOfExtractedImages;
    }

    /**
     * Extract images from ppt format files.
     *
     * @param af the file from which images are to be extracted.
     *
     * @return list of extracted images. Returns null in case no images were
     *         extracted.
     */
    private List<ExtractedImage> extractImagesFromPpt(AbstractFile af) {
        List<HSLFPictureData> listOfAllPictures = null;
        
        try {
            HSLFSlideShow ppt = new HSLFSlideShow(new ReadContentInputStream(af));
            listOfAllPictures = ppt.getPictureData();
        } catch (IOException | IllegalArgumentException |
                IndexOutOfBoundsException ex) {
            // IllegalArgumentException:
            // This will catch OldFileFormatException, which is thrown when the
            // document version is unsupported. The IllegalArgumentException may
            // also get thrown for unknown reasons.
            
            // IOException:
            // Thrown when the document has issues being read.
            
            // IndexOutOfBoundsException:
            // This gets thrown in certain images. The reason is unknown. It is
            // likely due to problems with the file formats that POI is poorly
            // handling.
            
            return null;
        } catch (Throwable ex) {
            // instantiating POI containers throw RuntimeExceptions
            logger.log(Level.SEVERE, NbBundle.getMessage(this.getClass(), "EmbeddedFileExtractorIngestModule.ImageExtractor.pptContainer.init.err", af.getName()), ex); //NON-NLS
            return null;
        }

        // if no images are extracted from the PPT, return null, else initialize
        // the output folder for image extraction.
        String outputFolderPath;
        if (listOfAllPictures.isEmpty()) {
            return null;
        } else {
            outputFolderPath = getOutputFolderPath(this.parentFileName);
        }
        if (outputFolderPath == null) {
            return null;
        }

        // extract the images to the above initialized outputFolder.
        // extraction path - outputFolder/image_number.ext
        int i = 0;
        List<ExtractedImage> listOfExtractedImages = new ArrayList<>();
        byte[] data = null;
        for (HSLFPictureData pictureData : listOfAllPictures) {

            // Get image extension, generate image name, write image to the module
            // output folder, add it to the listOfExtractedImageAbstractFiles
            PictureType type = pictureData.getType();
            String ext;
            switch (type) {
                case JPEG:
                    ext = ".jpg"; //NON-NLS
                    break;
                case PNG:
                    ext = ".png"; //NON-NLS
                    break;
                case WMF:
                    ext = ".wmf"; //NON-NLS
                    break;
                case EMF:
                    ext = ".emf"; //NON-NLS
                    break;
                case PICT:
                    ext = ".pict"; //NON-NLS
                    break;
                default:
                    continue;
            }
            String imageName = UNKNOWN_NAME_PREFIX + i + ext; //NON-NLS
            try {
                data = pictureData.getData();
            } catch (Exception ex) {
                return null;
            }
            writeExtractedImage(Paths.get(outputFolderPath, imageName).toString(), data);
            listOfExtractedImages.add(new ExtractedImage(imageName, getFileRelativePath(imageName), pictureData.getData().length, af));
            i++;
        }
        return listOfExtractedImages;
    }

    /**
     * Extract images from pptx format files.
     *
     * @param af the file from which images are to be extracted.
     *
     * @return list of extracted images. Returns null in case no images were
     *         extracted.
     */
    private List<ExtractedImage> extractImagesFromPptx(AbstractFile af) {
        List<XSLFPictureData> listOfAllPictures = null;
        
        try {
            XMLSlideShow pptx = new XMLSlideShow(new ReadContentInputStream(af));
            listOfAllPictures = pptx.getPictureData();
        } catch (POIXMLException | IOException ex) {
            // POIXMLException:
            // Thrown when document fails to load.
            
            // IOException:
            // Thrown when the document has issues being read
            
            return null;
        } catch (Throwable ex) {
            // instantiating POI containers throw RuntimeExceptions
            logger.log(Level.SEVERE, NbBundle.getMessage(this.getClass(), "EmbeddedFileExtractorIngestModule.ImageExtractor.pptxContainer.init.err", af.getName()), ex); //NON-NLS
            return null;
        }

        // if no images are extracted from the PPT, return null, else initialize
        // the output folder for image extraction.
        String outputFolderPath;
        if (listOfAllPictures.isEmpty()) {
            return null;
        } else {
            outputFolderPath = getOutputFolderPath(this.parentFileName);
        }
        if (outputFolderPath == null) {
            return null;
        }

        List<ExtractedImage> listOfExtractedImages = new ArrayList<>();
        byte[] data = null;
        for (XSLFPictureData xslsPicture : listOfAllPictures) {

            // get image file name, write it to the module outputFolder, and add
            // it to the listOfExtractedImageAbstractFiles.
            String fileName = xslsPicture.getFileName();
            try {
                data = xslsPicture.getData();
            } catch (Exception ex) {
                return null;
            }
            writeExtractedImage(Paths.get(outputFolderPath, fileName).toString(), data);
            listOfExtractedImages.add(new ExtractedImage(fileName, getFileRelativePath(fileName), xslsPicture.getData().length, af));

        }

        return listOfExtractedImages;

    }

    /**
     * Extract images from xls format files.
     *
     * @param af the file from which images are to be extracted.
     *
     * @return list of extracted images. Returns null in case no images were
     *         extracted.
     */
    private List<ExtractedImage> extractImagesFromXls(AbstractFile af) {
        List<? extends org.apache.poi.ss.usermodel.PictureData> listOfAllPictures = null;
        
        try {
            Workbook xls = new HSSFWorkbook(new ReadContentInputStream(af));
            listOfAllPictures = xls.getAllPictures();
        } catch (IOException | LeftoverDataException |
                RecordFormatException | IllegalArgumentException |
                IndexOutOfBoundsException ex) {
            // IllegalArgumentException:
            // This will catch OldFileFormatException, which is thrown when the
            // document version is unsupported. The IllegalArgumentException may
            // also get thrown for unknown reasons.
            
            // IOException:
            // Thrown when the document has issues being read.
            
            // LeftoverDataException:
            // This is thrown for poorly formatted files that have more data
            // than expected.
            
            // RecordFormatException:
            // This is thrown for poorly formatted files that have less data
            // that expected.
            
            // IllegalArgumentException:
            // IndexOutOfBoundsException:
            // These get thrown in certain images. The reason is unknown. It is
            // likely due to problems with the file formats that POI is poorly
            // handling.
            
            return null;
        } catch (Throwable ex) {
            // instantiating POI containers throw RuntimeExceptions
            logger.log(Level.SEVERE, String.format("%s%s", NbBundle.getMessage(this.getClass(), "EmbeddedFileExtractorIngestModule.ImageExtractor.xlsContainer.init.err", af.getName()), af.getName()), ex); //NON-NLS
            return null;
        }

        // if no images are extracted from the PPT, return null, else initialize
        // the output folder for image extraction.
        String outputFolderPath;
        if (listOfAllPictures.isEmpty()) {
            return null;
        } else {
            outputFolderPath = getOutputFolderPath(this.parentFileName);
        }
        if (outputFolderPath == null) {
            return null;
        }

        int i = 0;
        List<ExtractedImage> listOfExtractedImages = new ArrayList<>();
        byte[] data = null;
        for (org.apache.poi.ss.usermodel.PictureData pictureData : listOfAllPictures) {
            String imageName = UNKNOWN_NAME_PREFIX + i + "." + pictureData.suggestFileExtension(); //NON-NLS
            try {
                data = pictureData.getData();
            } catch (Exception ex) {
                return null;
            }
            writeExtractedImage(Paths.get(outputFolderPath, imageName).toString(), data);
            listOfExtractedImages.add(new ExtractedImage(imageName, getFileRelativePath(imageName), pictureData.getData().length, af));
            i++;
        }
        return listOfExtractedImages;

    }

    /**
     * Extract images from xlsx format files.
     *
     * @param af the file from which images are to be extracted.
     *
     * @return list of extracted images. Returns null in case no images were
     *         extracted.
     */
    private List<ExtractedImage> extractImagesFromXlsx(AbstractFile af) {
        List<? extends org.apache.poi.ss.usermodel.PictureData> listOfAllPictures = null;
        
        try {
            Workbook xlsx = new XSSFWorkbook(new ReadContentInputStream(af));
            listOfAllPictures = xlsx.getAllPictures();
        } catch (POIXMLException | IOException ex) {
            // POIXMLException:
            // Thrown when document fails to load.
            
            // IOException:
            // Thrown when the document has issues being read
            
            return null;
        } catch (Throwable ex) {
            // instantiating POI containers throw RuntimeExceptions
            logger.log(Level.SEVERE, NbBundle.getMessage(this.getClass(), "EmbeddedFileExtractorIngestModule.ImageExtractor.xlsxContainer.init.err", af.getName()), ex); //NON-NLS
            return null;
        }

        // if no images are extracted from the PPT, return null, else initialize
        // the output folder for image extraction.
        String outputFolderPath;
        if (listOfAllPictures.isEmpty()) {
            return null;
        } else {
            outputFolderPath = getOutputFolderPath(this.parentFileName);
        }
        if (outputFolderPath == null) {
            return null;
        }

        int i = 0;
        List<ExtractedImage> listOfExtractedImages = new ArrayList<>();
        byte[] data = null;
        for (org.apache.poi.ss.usermodel.PictureData pictureData : listOfAllPictures) {
            String imageName = UNKNOWN_NAME_PREFIX + i + "." + pictureData.suggestFileExtension();
            try {
                data = pictureData.getData();
            } catch (Exception ex) {
                return null;
            }
            writeExtractedImage(Paths.get(outputFolderPath, imageName).toString(), data);
            listOfExtractedImages.add(new ExtractedImage(imageName, getFileRelativePath(imageName), pictureData.getData().length, af));
            i++;
        }
        return listOfExtractedImages;

    }

    /**
     * Writes image to the module output location.
     *
     * @param outputPath Path where images is written
     * @param data       byte representation of the data to be written to the
     *                   specified location.
     */
    private void writeExtractedImage(String outputPath, byte[] data) {
        try (EncodedFileOutputStream fos = new EncodedFileOutputStream(new FileOutputStream(outputPath), TskData.EncodingType.XOR1)) {
            fos.write(data);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Could not write to the provided location: " + outputPath, ex); //NON-NLS
        }
    }

    /**
     * Gets path to the output folder for image extraction. If the path does not
     * exist, it is created.
     *
     * @param parentFileName name of the abstract file being processed for image
     *                       extraction.
     *
     * @return path to the image extraction folder for a given abstract file.
     */
    private String getOutputFolderPath(String parentFileName) {
        String outputFolderPath = moduleDirAbsolute + File.separator + parentFileName;
        File outputFilePath = new File(outputFolderPath);
        if (!outputFilePath.exists()) {
            try {
                outputFilePath.mkdirs();
            } catch (SecurityException ex) {
                logger.log(Level.WARNING, NbBundle.getMessage(this.getClass(), "EmbeddedFileExtractorIngestModule.ImageExtractor.getOutputFolderPath.exception.msg", parentFileName), ex);
                return null;
            }
        }
        return outputFolderPath;
    }

    /**
     * Gets the relative path to the file. The path is relative to the case
     * folder.
     *
     * @param fileName name of the the file for which the path is to be
     *                 generated.
     *
     * @return
     */
    private String getFileRelativePath(String fileName) {
        // Used explicit FWD slashes to maintain DB consistency across operating systems.
        return "/" + moduleDirRelative + "/" + this.parentFileName + "/" + fileName; //NON-NLS
    }

    /**
     * Represents the image extracted using POI methods. Currently, POI is not
     * capable of extracting ctime, crtime, mtime, and atime; these values are
     * set to 0.
     */
    private static class ExtractedImage {
        //String fileName, String localPath, long size, long ctime, long crtime, 
        //long atime, long mtime, boolean isFile, AbstractFile parentFile, String rederiveDetails, String toolName, String toolVersion, String otherDetails

        private final String fileName;
        private final String localPath;
        private final long size;
        private final long ctime;
        private final long crtime;
        private final long atime;
        private final long mtime;
        private final AbstractFile parentFile;

        ExtractedImage(String fileName, String localPath, long size, AbstractFile parentFile) {
            this(fileName, localPath, size, 0, 0, 0, 0, parentFile);
        }

        ExtractedImage(String fileName, String localPath, long size, long ctime, long crtime, long atime, long mtime, AbstractFile parentFile) {
            this.fileName = fileName;
            this.localPath = localPath;
            this.size = size;
            this.ctime = ctime;
            this.crtime = crtime;
            this.atime = atime;
            this.mtime = mtime;
            this.parentFile = parentFile;
        }

        public String getFileName() {
            return fileName;
        }

        public String getLocalPath() {
            return localPath;
        }

        public long getSize() {
            return size;
        }

        public long getCtime() {
            return ctime;
        }

        public long getCrtime() {
            return crtime;
        }

        public long getAtime() {
            return atime;
        }

        public long getMtime() {
            return mtime;
        }

        public AbstractFile getParentFile() {
            return parentFile;
        }
    }
}
