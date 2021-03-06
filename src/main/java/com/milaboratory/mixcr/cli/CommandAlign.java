/*
 * Copyright (c) 2014-2019, Bolotin Dmitry, Chudakov Dmitry, Shugay Mikhail
 * (here and after addressed as Inventors)
 * All Rights Reserved
 *
 * Permission to use, copy, modify and distribute any part of this program for
 * educational, research and non-profit purposes, by non-profit institutions
 * only, without fee, and without a written agreement is hereby granted,
 * provided that the above copyright notice, this paragraph and the following
 * three paragraphs appear in all copies.
 *
 * Those desiring to incorporate this work into commercial products or use for
 * commercial purposes should contact MiLaboratory LLC, which owns exclusive
 * rights for distribution of this program for commercial purposes, using the
 * following email address: licensing@milaboratory.com.
 *
 * IN NO EVENT SHALL THE INVENTORS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 * SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 * ARISING OUT OF THE USE OF THIS SOFTWARE, EVEN IF THE INVENTORS HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE SOFTWARE PROVIDED HEREIN IS ON AN "AS IS" BASIS, AND THE INVENTORS HAS
 * NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS. THE INVENTORS MAKES NO REPRESENTATIONS AND EXTENDS NO
 * WARRANTIES OF ANY KIND, EITHER IMPLIED OR EXPRESS, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A
 * PARTICULAR PURPOSE, OR THAT THE USE OF THE SOFTWARE WILL NOT INFRINGE ANY
 * PATENT, TRADEMARK OR OTHER RIGHTS.
 */
package com.milaboratory.mixcr.cli;

import cc.redberry.pipe.CUtils;
import cc.redberry.pipe.OutputPort;
import cc.redberry.pipe.blocks.Merger;
import cc.redberry.pipe.blocks.ParallelProcessor;
import cc.redberry.pipe.util.Chunk;
import cc.redberry.pipe.util.CountLimitingOutputPort;
import cc.redberry.pipe.util.OrderedOutputPort;
import cc.redberry.pipe.util.StatusReporter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.milaboratory.cli.ActionConfiguration;
import com.milaboratory.cli.PipelineConfiguration;
import com.milaboratory.core.PairedEndReadsLayout;
import com.milaboratory.core.Target;
import com.milaboratory.core.io.sequence.SequenceRead;
import com.milaboratory.core.io.sequence.SequenceReaderCloseable;
import com.milaboratory.core.io.sequence.SequenceWriter;
import com.milaboratory.core.io.sequence.fasta.FastaReader;
import com.milaboratory.core.io.sequence.fasta.FastaSequenceReaderWrapper;
import com.milaboratory.core.io.sequence.fastq.PairedFastqReader;
import com.milaboratory.core.io.sequence.fastq.PairedFastqWriter;
import com.milaboratory.core.io.sequence.fastq.SingleFastqReader;
import com.milaboratory.core.io.sequence.fastq.SingleFastqWriter;
import com.milaboratory.core.sequence.NucleotideSequence;
import com.milaboratory.core.sequence.quality.QualityTrimmerParameters;
import com.milaboratory.core.sequence.quality.ReadTrimmerProcessor;
import com.milaboratory.core.sequence.quality.ReadTrimmerReport;
import com.milaboratory.mixcr.basictypes.SequenceHistory;
import com.milaboratory.mixcr.basictypes.VDJCAlignments;
import com.milaboratory.mixcr.basictypes.VDJCAlignmentsWriter;
import com.milaboratory.mixcr.basictypes.VDJCHit;
import com.milaboratory.mixcr.util.MiXCRVersionInfo;
import com.milaboratory.mixcr.vdjaligners.VDJCAligner;
import com.milaboratory.mixcr.vdjaligners.VDJCAlignerParameters;
import com.milaboratory.mixcr.vdjaligners.VDJCAlignmentResult;
import com.milaboratory.mixcr.vdjaligners.VDJCParametersPresets;
import com.milaboratory.util.CanReportProgress;
import com.milaboratory.util.SmartProgressReporter;
import io.repseq.core.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

import static cc.redberry.pipe.CUtils.chunked;
import static cc.redberry.pipe.CUtils.unchunked;
import static com.milaboratory.mixcr.basictypes.AlignmentsIO.DEFAULT_ALIGNMENTS_IN_BLOCK;
import static com.milaboratory.mixcr.cli.CommandAlign.ALIGN_COMMAND_NAME;

@Command(name = ALIGN_COMMAND_NAME,
        sortOptions = false,
        separator = " ",
        description = "Builds alignments with V,D,J and C genes for input sequencing reads.")
public class CommandAlign extends ACommandWithSmartOverwriteMiXCR {
    static final String ALIGN_COMMAND_NAME = "align";
    @Parameters(arity = "2..3",
            descriptionKey = "file",
            paramLabel = "files",
            hideParamSyntax = true,
            description = "file_R1.(fastq[.gz]|fasta) [file_R2.fastq[.gz]] alignments.vdjca")
    private List<String> inOut = new ArrayList<>();

    @Override
    public List<String> getInputFiles() {
        return inOut.subList(0, inOut.size() - 1);
    }

    @Override
    protected List<String> getOutputFiles() {
        return inOut.subList(inOut.size() - 1, inOut.size());
    }

    @Option(description = CommonDescriptions.SPECIES,
            names = {"-s", "--species"},
            required = true)
    public String species = null;

    @Option(description = CommonDescriptions.REPORT,
            names = {"-r", "--report"})
    public String reportFile = null;

    @Option(description = CommonDescriptions.JSON_REPORT,
            names = {"-j", "--json-report"})
    public String jsonReport = null;

    @Option(description = "V/D/J/C gene library",
            names = {"-b", "--library"})
    public String library = "default";

    public int threads = Runtime.getRuntime().availableProcessors();

    @Option(description = "Processing threads",
            names = {"-t", "--threads"})
    public void setThreads(int threads) {
        if (threads <= 0)
            throwValidationException("ERROR: -t / --threads must be positive", false);
        this.threads = threads;
    }

    public long limit = 0;

    @Option(description = "Maximal number of reads to process",
            names = {"-n", "--limit"})
    public void setLimit(int limit) {
        if (limit <= 0)
            throwValidationException("ERROR: -n / --limit must be positive", false);
        this.limit = limit;
    }

    @Option(description = "Read pre-processing: trimming quality threshold",
            names = {"--trimming-quality-threshold"})
    public byte trimmingQualityThreshold = 0; // 17

    @Option(description = "Read pre-processing: trimming window size",
            names = {"--trimming-window-size"})
    public byte trimmingWindowSize = 6; // 3

    @Option(description = "Parameters preset.",
            names = {"-p", "--parameters"})
    public String alignerParametersName = "default";

    @Option(names = {"-O"}, description = "Overrides default aligner parameter values")
    public Map<String, String> overrides = new HashMap<>();

    public String chains = "ALL";

    @Option(description = "Specifies immunological chain / gene(s) for alignment. If many, separate by comma ','. " +
            "%nAvailable chains: IGH, IGL, IGK, TRA, TRB, TRG, TRD, etc...",
            names = {"-c", "--chains"},
            hidden = true)
    public void setChains(String chains) {
        warn("Don't use --chains option on the alignment step. See --chains parameter in exportAlignments and " +
                "exportClones actions to limit output to a subset of receptor chains.");
        this.chains = chains;
    }

    @Option(description = "Do not merge paired reads.",
            names = {"-d", "--no-merge"},
            hidden = true)
    public boolean noMerge = false;

    @Deprecated
    @Option(description = "Copy read(s) description line from .fastq or .fasta to .vdjca file (can then be " +
            "exported with -descrR1 and -descrR2 options in exportAlignments action).",
            names = {"-a", "--save-description"},
            hidden = true)
    public void setSaveReadDescription(boolean b) {
        throwValidationException("--save-description was removed in 3.0: use -OsaveOriginalReads=true instead");
    }

    @Option(description = "Write alignment results for all input reads (even if alignment failed).",
            names = {"--write-all"})
    public boolean writeAllResults = false;

    @Deprecated
    @Option(description = "Copy original reads (sequences + qualities + descriptions) to .vdjca file.",
            names = {"-g", "--save-reads"},
            hidden = true)
    public void setSaveOriginalReads(boolean b) {
        throwValidationException("--save-reads was removed in 3.0: use -OsaveOriginalReads=true instead");
    }

    @Option(description = "Pipe not aligned R1 reads into separate file.",
            names = {"--not-aligned-R1"})
    public String failedReadsR1 = null;

    @Option(description = "Pipe not aligned R2 reads into separate file.",
            names = {"--not-aligned-R2"})
    public String failedReadsR2 = null;

    @Option(description = "Show runtime buffer load.",
            names = {"--buffers"}, hidden = true)
    public boolean reportBuffers = false;

    private VDJCAlignerParameters vdjcAlignerParameters = null;

    public VDJCAlignerParameters getAlignerParameters() {
        if (vdjcAlignerParameters != null)
            return vdjcAlignerParameters;

        VDJCAlignerParameters alignerParameters = VDJCParametersPresets.getByName(alignerParametersName);
        if (alignerParameters == null)
            throwValidationException("Unknown aligner parameters: " + alignerParametersName);

        if (!overrides.isEmpty()) {
            // Perform parameters overriding
            alignerParameters = JsonOverrider.override(alignerParameters, VDJCAlignerParameters.class, overrides);
            if (alignerParameters == null)
                throwValidationException("Failed to override some parameter: " + overrides);
        }

        // Detect if automatic featureToAlign correction is required
        VDJCLibrary library = getLibrary();

        int totalV = 0, totalVErrors = 0, hasVRegion = 0;
        GeneFeature correctingFeature = alignerParameters.getVAlignerParameters().getGeneFeatureToAlign().hasReversedRegions() ?
                GeneFeature.VRegionWithP :
                GeneFeature.VRegion;

        for (VDJCGene gene : library.getGenes(getChains())) {
            if (gene.getGeneType() == GeneType.Variable) {
                totalV++;
                if (!alignerParameters.containsRequiredFeature(gene)) {
                    totalVErrors++;
                    if (gene.getPartitioning().isAvailable(correctingFeature))
                        hasVRegion++;
                }
            }
        }

        // Performing V featureToAlign correction if needed
        if (totalVErrors > totalV * 0.9 && hasVRegion > totalVErrors * 0.8) {
            warn("WARNING: forcing -OvParameters.geneFeatureToAlign=" + GeneFeature.encode(correctingFeature) +
                    " since current gene feature (" + GeneFeature.encode(alignerParameters.getVAlignerParameters().getGeneFeatureToAlign()) + ") is absent in " +
                    Util.PERCENT_FORMAT.format(100.0 * totalVErrors / totalV) + "% of V genes.");
            alignerParameters.getVAlignerParameters().setGeneFeatureToAlign(correctingFeature);
        }

        return vdjcAlignerParameters = alignerParameters;
    }

    public Chains getChains() {
        return Chains.parse(chains);
    }

    private VDJCLibrary vdjcLibrary = null;

    static final Pattern libraryNameEnding = Pattern.compile("\\.json(?:\\.gz|)$");

    public String getLibraryName() {
        return libraryNameEnding.matcher(library).replaceAll("");
    }

    public VDJCLibrary getLibrary() {
        return vdjcLibrary != null
                ? vdjcLibrary
                : (vdjcLibrary = VDJCLibraryRegistry.getDefault().getLibrary(getLibraryName(), species));
    }

    public boolean isInputPaired() {
        return getInputFiles().size() == 2;
    }

    public SequenceReaderCloseable<? extends SequenceRead> createReader() throws IOException {
        if (isInputPaired())
            return new PairedFastqReader(getInputFiles().get(0), getInputFiles().get(1), true);
        else {
            String in = getInputFiles().get(0);
            String[] s = in.split("\\.");
            if (s[s.length - 1].equals("fasta") || s[s.length - 1].equals("fa"))
                return new FastaSequenceReaderWrapper(
                        new FastaReader<>(in, NucleotideSequence.ALPHABET),
                        true
                );
            else
                return new SingleFastqReader(in, true);
        }
    }

    @Override
    public PipelineConfiguration getFullPipelineConfiguration() {
        return PipelineConfiguration.mkInitial(getInputFiles(), getConfiguration(),
                MiXCRVersionInfo.getAppVersionInfo());
    }

    @Override
    public ActionConfiguration getConfiguration() {
        return new AlignConfiguration(
                getAlignerParameters(),
                !noMerge,
                getLibrary().getLibraryId(),
                limit,
                getQualityTrimmerParameters());
    }

    /** Set of parameters that completely (uniquely) determine align action */
    @JsonAutoDetect(
            fieldVisibility = JsonAutoDetect.Visibility.ANY,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE,
            getterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.CLASS,
            include = JsonTypeInfo.As.PROPERTY,
            property = "type")
    public static class AlignConfiguration implements ActionConfiguration {
        /**
         * Aligner parameters
         */
        public final VDJCAlignerParameters alignerParameters;
        /**
         * Whether reads were merged
         */
        public final boolean mergeReads;
        /**
         * VDJC library ID
         */
        public final VDJCLibraryId libraryId;
        /**
         * Limit number of reads
         */
        public final long limit;
        /**
         * Trimming parameters (null if trimming is not enabled)
         */
        public final QualityTrimmerParameters trimmerParameters;

        @JsonCreator
        public AlignConfiguration(@JsonProperty("alignerParameters") VDJCAlignerParameters alignerParameters,
                                  @JsonProperty("mergeReads") boolean mergeReads,
                                  @JsonProperty("libraryId") VDJCLibraryId libraryId,
                                  @JsonProperty("limit") long limit,
                                  @JsonProperty("trimmerParameters") QualityTrimmerParameters trimmerParameters) {
            this.alignerParameters = alignerParameters;
            this.mergeReads = mergeReads;
            this.libraryId = libraryId;
            this.limit = limit;
            this.trimmerParameters = trimmerParameters;
        }

        @Override
        public String actionName() {
            return ALIGN_COMMAND_NAME;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AlignConfiguration that = (AlignConfiguration) o;
            return mergeReads == that.mergeReads &&
                    limit == that.limit &&
                    Objects.equals(alignerParameters, that.alignerParameters) &&
                    Objects.equals(libraryId, that.libraryId) &&
                    Objects.equals(trimmerParameters, that.trimmerParameters);
        }

        @Override
        public int hashCode() {
            return Objects.hash(alignerParameters, mergeReads, libraryId, limit, trimmerParameters);
        }
    }

    @Override
    public void validate() {
        super.validate();
        if (inOut.size() > 3)
            throwValidationException("Too many input files.");
        if (inOut.size() < 2)
            throwValidationException("No output file.");
        if (failedReadsR2 != null && failedReadsR1 == null)
            throwValidationException("Wrong input for --not-aligned-R1,2");
        if (failedReadsR1 != null && (failedReadsR2 != null) != isInputPaired())
            throwValidationException("Option --not-aligned-R2 is not set.", false);
        if (library.contains("/") || library.contains("\\"))
            throwValidationException("Library name can't be a path. Place your library to one of the " +
                    "library search locations (e.g. '" + Paths.get(System.getProperty("user.home"), ".mixcr", "libraries", "mylibrary.json").toString() +
                    "', and put just a library name as -b / --library option value (e.g. '--library mylibrary').", false);
    }

    /** Alignment report */
    public final AlignerReport report = new AlignerReport();

    private QualityTrimmerParameters getQualityTrimmerParameters() {
        return new QualityTrimmerParameters(trimmingQualityThreshold,
                trimmingWindowSize);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run1() throws Exception {
        // Saving initial timestamp
        long beginTimestamp = System.currentTimeMillis();

        // Getting aligner parameters
        VDJCAlignerParameters alignerParameters = getAlignerParameters();

        // Detect if automatic featureToAlign correction is required
        VDJCLibrary library = getLibrary();

        // Printing library level warnings, if specified for the library
        if (!library.getWarnings().isEmpty()) {
            warn("Library warnings:");
            for (String l : library.getWarnings())
                warn(l);
        }

        // Printing citation notice, if specified for the library
        if (!library.getCitations().isEmpty()) {
            warn("Please cite:");
            for (String l : library.getCitations())
                warn(l);
        }

        // Creating aligner
        VDJCAligner aligner = VDJCAligner.createAligner(alignerParameters,
                isInputPaired(), !noMerge);

        int numberOfExcludedNFGenes = 0;
        int numberOfExcludedFGenes = 0;
        for (VDJCGene gene : library.getGenes(getChains())) {
            NucleotideSequence featureSequence = alignerParameters.extractFeatureToAlign(gene);

            // exclusionReason is null ==> gene is not excluded
            String exclusionReason = null;
            if (featureSequence == null)
                exclusionReason = "absent " + GeneFeature.encode(alignerParameters.getFeatureToAlign(gene.getGeneType()));
            else if (featureSequence.containsWildcards())
                exclusionReason = "wildcard symbols in " + GeneFeature.encode(alignerParameters.getFeatureToAlign(gene.getGeneType()));

            if (exclusionReason == null)
                aligner.addGene(gene); // If there are no reasons to exclude the gene, adding it to aligner
            else {
                if (gene.isFunctional()) {
                    ++numberOfExcludedFGenes;
                    if (verbose)
                        warn("WARNING: Functional gene " + gene.getName() + " excluded due to " + exclusionReason);
                } else
                    ++numberOfExcludedNFGenes;
            }
        }

        if (numberOfExcludedFGenes > 0)
            warn("WARNING: " + numberOfExcludedFGenes + " functional genes were excluded, re-run " +
                    "with --verbose option to see the list of excluded genes and exclusion reason.");

        if (verbose && numberOfExcludedNFGenes > 0)
            warn("WARNING: " + numberOfExcludedNFGenes + " non-functional genes excluded.");

        if (aligner.getVGenesToAlign().isEmpty())
            throwExecutionException("No V genes to align. Aborting execution. See warnings for more info " +
                    "(turn on verbose warnings by adding --verbose option).");

        if (aligner.getJGenesToAlign().isEmpty())
            throwExecutionException("No J genes to align. Aborting execution. See warnings for more info " +
                    "(turn on verbose warnings by adding --verbose option).");


        report.setStartMillis(beginTimestamp);
        report.setInputFiles(getInputFiles());
        report.setOutputFiles(getOutput());
        report.setCommandLine(getCommandLineArguments());

        // Attaching report to aligner
        aligner.setEventsListener(report);

        try (SequenceReaderCloseable<? extends SequenceRead> reader = createReader();

             VDJCAlignmentsWriter writer = getOutput().equals(".")
                     ? null
                     : new VDJCAlignmentsWriter(getOutput(), Math.max(1, threads / 8),
                     DEFAULT_ALIGNMENTS_IN_BLOCK);

             SequenceWriter notAlignedWriter = failedReadsR1 == null
                     ? null
                     : (isInputPaired()
                     ? new PairedFastqWriter(failedReadsR1, failedReadsR2)
                     : new SingleFastqWriter(failedReadsR1));
        ) {
            if (writer != null)
                writer.header(aligner, getFullPipelineConfiguration());

            OutputPort<? extends SequenceRead> sReads = reader;
            CanReportProgress progress = (CanReportProgress) reader;
            if (limit != 0) {
                sReads = new CountLimitingOutputPort<>(sReads, limit);
                progress = SmartProgressReporter.extractProgress((CountLimitingOutputPort<?>) sReads);
            }

            EnumMap<GeneType, VDJCHit[]> emptyHits = new EnumMap<>(GeneType.class);
            for (GeneType gt : GeneType.values())
                if (alignerParameters.getGeneAlignerParameters(gt) != null)
                    emptyHits.put(gt, new VDJCHit[0]);
            final PairedEndReadsLayout readsLayout = alignerParameters.getReadsLayout();

            SmartProgressReporter.startProgressReport("Alignment", progress);
            Merger<Chunk<? extends SequenceRead>> mainInputReads = CUtils.buffered((OutputPort) chunked(sReads, 64), Math.max(16, threads));

            OutputPort<Chunk<? extends SequenceRead>> mainInputReadsPreprocessed = mainInputReads;
            if (trimmingQualityThreshold > 0) {
                ReadTrimmerReport rep = new ReadTrimmerReport();
                mainInputReadsPreprocessed = CUtils.wrap(
                        mainInputReadsPreprocessed,
                        CUtils.chunked(new ReadTrimmerProcessor(getQualityTrimmerParameters(), rep)));
                report.setTrimmingReport(rep);
            }

            ParallelProcessor alignedChunks = new ParallelProcessor(mainInputReadsPreprocessed, chunked(aligner), Math.max(16, threads), threads);
            if (reportBuffers) {
                StatusReporter reporter = new StatusReporter();
                reporter.addBuffer("Input (chunked; chunk size = 64)", mainInputReads.getBufferStatusProvider());
                reporter.addBuffer("Alignment result (chunked; chunk size = 64)", alignedChunks.getOutputBufferStatusProvider());
                reporter.addCustomProvider(new StatusReporter.StatusProvider() {
                    volatile String status;
                    volatile boolean isClosed = false;

                    @Override
                    public void updateStatus() {
                        status = "Busy encoders: " + writer.getBusyEncoders() + " / " + writer.getEncodersCount();
                        isClosed = writer.isClosed();
                    }

                    @Override
                    public boolean isFinished() {
                        return isClosed;
                    }

                    @Override
                    public String getStatus() {
                        return status;
                    }
                });
                reporter.start();
            }
            OutputPort<VDJCAlignmentResult> alignments = unchunked(
                    CUtils.wrap(alignedChunks,
                            CUtils.<VDJCAlignmentResult, VDJCAlignmentResult>chunked(VDJCAlignmentResult::shiftIndelsAtHomopolymers)));
            for (VDJCAlignmentResult result : CUtils.it(new OrderedOutputPort<>(alignments, o -> o.read.getId()))) {
                VDJCAlignments alignment = result.alignment;
                SequenceRead read = result.read;
                if (alignment == null) {
                    if (writeAllResults)
                    // Creating empty alignment object if alignment for current read failed
                    {
                        Target target = readsLayout.createTargets(read)[0];
                        alignment = new VDJCAlignments(emptyHits,
                                target.targets,
                                SequenceHistory.RawSequence.of(read.getId(), target),
                                alignerParameters.isSaveOriginalReads() ? new SequenceRead[]{read} : null);
                    } else {
                        if (notAlignedWriter != null)
                            notAlignedWriter.write(result.read);
                        continue;
                    }
                }

                if (alignment.isChimera())
                    report.onChimera();

                if (writer != null)
                    writer.write(alignment);
            }
            if (writer != null)
                writer.setNumberOfProcessedReads(reader.getNumberOfReads());
        }

        report.setFinishMillis(System.currentTimeMillis());

        // Writing report to stout
        System.out.println("============= Report ==============");
        Util.writeReportToStdout(report);

        if (reportFile != null)
            Util.writeReport(reportFile, report);

        if (jsonReport != null)
            Util.writeJsonReport(jsonReport, report);
    }
}
