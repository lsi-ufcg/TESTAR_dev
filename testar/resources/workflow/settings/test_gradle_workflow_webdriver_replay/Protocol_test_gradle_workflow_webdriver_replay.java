/**
 * Copyright (c) 2021 - 2023 Open Universiteit - www.ou.nl
 * Copyright (c) 2021 - 2023 Universitat Politecnica de Valencia - www.upv.es
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 */

import org.apache.commons.io.FileUtils;
import org.testar.monkey.alayer.*;
import org.testar.monkey.alayer.actions.AnnotatingActionCompiler;
import org.testar.monkey.alayer.actions.StdActionCompiler;
import org.testar.monkey.alayer.exceptions.ActionBuildException;
import org.testar.monkey.alayer.webdriver.enums.WdTags;
import org.testar.monkey.Assert;
import org.testar.monkey.ConfigTags;
import org.testar.monkey.Main;
import org.testar.settings.Settings;
import org.testar.OutputStructure;
import org.testar.managers.InputDataManager;
import org.testar.protocols.WebdriverProtocol;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;

import static org.testar.monkey.alayer.Tags.Blocked;
import static org.testar.monkey.alayer.Tags.Enabled;
import static org.testar.monkey.alayer.webdriver.Constants.scrollArrowSize;
import static org.testar.monkey.alayer.webdriver.Constants.scrollThick;

/**
 * This protocol is used to test TESTAR by executing a gradle CI workflow.
 * 
 * ".github/workflows/gradle.yml"
 */
public class Protocol_test_gradle_workflow_webdriver_replay extends WebdriverProtocol {

    /**
     * Called once during the life time of TESTAR
     * This method can be used to perform initial setup work
     *
     * @param settings the current TESTAR settings as specified by the user.
     */
    @Override
    protected void initialize(Settings settings) {
        super.initialize(settings);

        try {
            // Get the name of the web page file and obtain the file path
            String sutConValue = settings.get(ConfigTags.SUTConnectorValue, "");
            String webPage = sutConValue.split(" ")[1].replace("\"", "");
            String pathWebPage = new File(webPage).getCanonicalPath();
            settings.set(ConfigTags.SUTConnectorValue, sutConValue.replace(webPage, pathWebPage));
        } catch(Exception e) {System.err.println("ERROR: Obtaining the canonical pathWebPage to replay");}
    }

    @Override
    protected Set<Action> deriveActions(SUT system, State state)
            throws ActionBuildException {
        // Kill unwanted processes, force SUT to foreground
        Set<Action> actions = super.deriveActions(system, state);

        // create an action compiler, which helps us create actions
        // such as clicks, drag&drop, typing ...
        StdActionCompiler ac = new AnnotatingActionCompiler();

        // Check if forced actions are needed to stay within allowed domains
        Set<Action> forcedActions = detectForcedActions(state, ac);
        if (forcedActions != null && forcedActions.size() > 0) {
            return forcedActions;
        }

        // iterate through all widgets
        for (Widget widget : state) {
            // only consider enabled and non-tabu widgets
            if (!widget.get(Enabled, true) || blackListed(widget)) {
                continue;
            }

            // slides can happen, even though the widget might be blocked
            addSlidingActions(actions, ac, scrollArrowSize, scrollThick, widget, state);

            // If the element is blocked, Testar can't click on or type in the widget
            if (widget.get(Blocked, false) && !widget.get(WdTags.WebIsShadow, false)) {
                continue;
            }

            // type into text boxes
            if (isAtBrowserCanvas(widget) && isTypeable(widget) && (whiteListed(widget) || isUnfiltered(widget))) {
                actions.add(ac.clickTypeInto(widget, InputDataManager.getRandomTextInputData(widget), true));
            }

            // left clicks, but ignore links outside domain
            if (isAtBrowserCanvas(widget) && isClickable(widget) && (whiteListed(widget) || isUnfiltered(widget))) {
                if (!isLinkDenied(widget)) {
                    actions.add(ac.leftClickAt(widget));
                }
            }
        }

        return actions;
    }

    @Override
    protected void postSequenceProcessing() {
    	super.postSequenceProcessing(); // Finish Reports

    	// Verify html and txt report files were created
    	File htmlReportFile = new File(reportManager.getReportFileName().concat("_" + getReplayVerdict().verdictSeverityTitle() + ".html"));
    	File txtReportFile = new File(reportManager.getReportFileName().concat("_" + getReplayVerdict().verdictSeverityTitle() + ".txt"));
    	System.out.println("htmlReportFile: " + htmlReportFile.getPath());
    	System.out.println("txtReportFile: " + txtReportFile.getPath());
    	Assert.isTrue(htmlReportFile.exists());
    	Assert.isTrue(txtReportFile.exists());

    	// Verify report information
    	Assert.isTrue(fileContains("<h1>TESTAR replay sequence report", htmlReportFile));
    	Assert.isTrue(fileContains("TESTAR replay sequence report", txtReportFile));

    	Assert.isTrue(fileContains("<h2>Test verdict for this sequence:", htmlReportFile));
    	Assert.isTrue(fileContains("Test verdict for this sequence:", txtReportFile));
    }

    private boolean fileContains(String searchText, File file) {
    	try (Scanner scanner = new Scanner(file)) {
    		// Read the content of the file line by line
    		while (scanner.hasNextLine()) {
    			String line = scanner.nextLine();

    			// Check if the line contains the specific text
    			if (line.contains(searchText)) {
    				return true;
    			}
    		}
    	} catch (FileNotFoundException e) {
    		e.printStackTrace();
    	}
    	return false;
    }

    @Override
    protected void closeTestSession() {
        super.closeTestSession();
        try {
            File originalFolder = new File(OutputStructure.outerLoopOutputDir).getCanonicalFile();
            File artifactFolder = new File(Main.testarDir + settings.get(ConfigTags.ApplicationName,""));
            FileUtils.copyDirectory(originalFolder, artifactFolder);
        } catch(Exception e) {System.out.println("ERROR: Creating Artifact Folder");}
    }

}
