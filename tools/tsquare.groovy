#!/usr/bin/env groovy
/*
 * Copyright (C) 2013 Conductor, Inc.
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

/*
::=========================================================================:: 
Command line utility to interact with TSquare.

Author James Royalty (jroyalty)
::=========================================================================:: 
*/

import groovy.json.*
import org.apache.commons.cli.*
import javax.swing.WindowConstants as WC
import java.util.concurrent.TimeUnit

@Grab(group='org.jfree', module='jfreechart', version='1.0.14')
import org.jfree.chart.*
import org.jfree.chart.renderer.xy.SamplingXYLineRenderer
import org.jfree.data.time.*

def printlnVerbose(message) {
    if (VERBOSE) {
	System.err.println message
    }
}

def newExtendedQueryEndpoint(String tsquareBaseUrl) {
    return "${tsquareBaseUrl}/ext/q"
}

def newGrepEndpoint(String tsquareBaseUrl) {
    return "${tsquareBaseUrl}/ext/grep"
}

def displayGraph(String metricsRawJson, boolean samplingRenderer = true) {
    def slurper = new JsonSlurper()
    def parsedJson = slurper.parseText(metricsRawJson)

    def numInstruments = 0
    def numDatapoints = 0

    def dataset = new TimeSeriesCollection()

    // Walk over the parsed JSON document. This assumes a Graphite-like JSON format
    // as described in http://graphite.readthedocs.org/en/latest/render_api.html
    // NOTE that this doesn't come from Graphite web; we are simply using Graphite's
    // JSON format.
    parsedJson.each() { seriesJson->
	numInstruments++

	def series = new TimeSeries(seriesJson.target)
	seriesJson.datapoints.each() { point->
	    numDatapoints++

	    series.add(
		new FixedMillisecond(TimeUnit.SECONDS.toMillis(point[1] as Long)),
		point[0] as Double
	    )
	}

	dataset.addSeries(series)
    }

    def summary = "${numInstruments} series with ${numDatapoints} total datapoints"

    printlnVerbose "Found ${summary}."

    def chart = ChartFactory.createTimeSeriesChart(
        null,
        "Time", // domain axis label
        "Value", // range axis label
        dataset, // data
        true, // create legend?
        true, // generate tooltips?        
        false // generate URLs?
    )

    if (samplingRenderer) {
	// This only draws parts of the graph that are likely to change it's
	// appearance.  Therefore, we end up throwing away some points.
	def lineRenderer = new SamplingXYLineRenderer()
	chart.plot.renderer = lineRenderer
    }
    else {
	// Show data point markers when we aren't using a sampling renderer.
	chart.plot.renderer.shapesVisible = true
    }

    // Using the ChartFrame allows us to auto-resize with the window
    def frame = new ChartFrame("Showing ${summary}.", chart, true)
    frame.defaultCloseOperation = WC.EXIT_ON_CLOSE

    frame.pack()
    frame.show()
}

def displaySummaryInfo(String metricsRawJson, boolean prettyPrint) {
    if (prettyPrint) {
	println JsonOutput.prettyPrint(metricsRawJson)
    } else {
	def slurper = new JsonSlurper()
	def parsedJson = slurper.parseText(metricsRawJson)

	parsedJson.each() { seriesJson->
	    println "${seriesJson.target}=${seriesJson.summarizedValue}"
	}
    }
}

def handleQueryMode(cmdlineOpts) {
    def queryParams = []

    if (cmdlineOpts.'start') {
	queryParams << "start=${cmdlineOpts.'start'}"
    }
    else {
	// Default to 24 hours ago.
	queryParams << "start=-24h"
    }

    if (cmdlineOpts.'end') {
	queryParams << "end=${cmdlineOpts.'end'}"
    }

    if (cmdlineOpts.'metrics') {
	// Note that 'metricss' is not a typo.  This handles the case where multiple
	// --metricss arguments were given.  The 's' suffix has that effect.
	cmdlineOpts.'metricss'.each() { joinedName->
	    joinedName.split(",").each() { name->
		queryParams << "m=${name}"
	    }
	}
    }

    if (cmdlineOpts.'summarize' || cmdlineOpts.'summarize-pretty') {
	queryParams << "summarize=true"
    }

    def urlString = newExtendedQueryEndpoint(TSQUARE_BASEURL)
    urlString += "?${queryParams.join("&")}"
    
    def url = new URL(urlString)
    printlnVerbose url

    if (cmdlineOpts.'raw') {
	println url.text
    }
    else if (cmdlineOpts.'summarize' || cmdlineOpts.'summarize-pretty') {
	displaySummaryInfo(url.text, cmdlineOpts.'summarize-pretty')
    }
    else if (cmdlineOpts.'graph') {
	displayGraph(url.text, !cmdlineOpts.'no-sampling')
    }
    else {
	println JsonOutput.prettyPrint(url.text)
    }
}

def handleSearchMode(cmdlineOpts) {
    def queryParams = []

    // Query method. Wildcard is the default.
    if (cmdlineOpts.'regex') {
	queryParams << "method=regex"
    }

    // The search expression.
    def expression = cmdlineOpts.getArgList().first()
    assert expression

    queryParams << "q=${java.net.URLEncoder.encode(expression, "UTF-8")}"

    def urlString = newGrepEndpoint(TSQUARE_BASEURL)
    urlString += "?${queryParams.join("&")}"
    
    def url = new URL(urlString)
    printlnVerbose url

    if (cmdlineOpts.'raw') {
	println url.text
    }
    else {
	def slurper = new JsonSlurper()
	def json = slurper.parseText(url.text)

	if (!json) {
	    printlnVerbose "No results."
	}
	else {
	    def numResults = 0

	    // The result is an array with string entries, so iterate over those.
	    json.each() { entry->
		println entry
		numResults++
	    }

	    printlnVerbose "Found ${numResults} results."
	}
    }
}

// ::-------------------------------------------------------------------------:: 
// MAIN
// ::-------------------------------------------------------------------------:: 

def cli = new CliBuilder(
    usage:"${this.class.simpleName} [--query | --search] [options]", 
    header:"Query instruments service and return JSON.\n"
)

cli.h(longOpt:"help", "Show help, which is what you are reading now.")
cli._(longOpt:"url", args:1, "Override the TSquare base URL set in the TSQUARE_URL environment variable.")

def modeGroup = new OptionGroup()
modeGroup.addOption( new Option("q", "query", false, "Query for time series data. (Default)") )
modeGroup.addOption( new Option("s", "search", false, "Search for metrics and/or tag metadata.") )
modeGroup.required = false
cli.options.addOptionGroup(modeGroup)

cli.r(longOpt:"regex", 
	"[search] Instead of searching by wildcard, which is the default default,"
	+ " search using a regular expression.")

cli.m(longOpt:"metrics", args:1, "Metric names to search or query for.")

cli.f(longOpt:"start", args:1, argName:"date/time expression",
	"[query] Defines the beginning time of the query; e.g. the start time."
	+ " Can be given in absolute or relative form.  If unspecified, the default"
	+ " is 24 hours ago.")

cli.u(longOpt:"end", args:1, argName:"date/time expression",
	"[query] Defines the end time of the query. Can be given in absolute or"
	+ " relative form.  if unspecified, the default is NOW.")

cli._(longOpt:"summarize",
	"[query] Return the results of the query as a single value per-metric."
	+ " Note that the query results are summarized using whatever aggregator"
	+ " you specified for each metric.")

cli._(longOpt:"summarize-pretty",
	"[query] Same as --summarize, but this option causes the output to be"
	+ " pretty-printed JSON.")

cli.g(longOpt:"graph", 
	"[query] Graph the data points returned by the query.  NOTE: This command"
	+ " will open a new window for the graph.")

cli._(longOpt:"raw",
	"Print raw output, as returned by the web service, to standard output.")

cli._(longOpt:"no-sampling",
	"When drawing the graph, do not sample input points.  Just draw them as given.")

cli.V(longOpt:"verbose",
	"Enable verbose output.  This gets written to standard error.")

if (!args) {
    cli.usage()
    return
}

def opts = cli.parse(args)
if (!opts) {
    return
}
else if (opts.'help') {
    cli.usage()
    return
}

// The base URL, default or set via command line.
def baseUrl = System.getenv()['TSQUARE_URL']
if (opts.'url') {
    baseUrl = opts.'url'
}

// Make sure it was set somewhere!
assert baseUrl

if (baseUrl.endsWith("/")) {
    baseUrl = baseUrl.substring(0, baseUrl.size()-1)
}

VERBOSE = opts.'verbose'
TSQUARE_BASEURL = baseUrl

// Time our execution...
def startTs = System.currentTimeMillis()

if (opts.'search') {
    handleSearchMode(opts)
}
else {
    // Query mode is the default.
    handleQueryMode(opts)
}

def diffSeconds = ( System.currentTimeMillis() - startTs ) / 1000
printlnVerbose "Done.  Execution took ${diffSeconds} seconds."
