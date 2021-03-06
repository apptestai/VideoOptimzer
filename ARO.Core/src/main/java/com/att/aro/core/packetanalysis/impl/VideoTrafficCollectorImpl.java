/*

 *  Copyright 2017 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.att.aro.core.packetanalysis.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.regex.Pattern;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.att.aro.core.commandline.IExternalProcessRunner;
import com.att.aro.core.fileio.IFileManager;
import com.att.aro.core.packetanalysis.IHttpRequestResponseHelper;
import com.att.aro.core.packetanalysis.IVideoTrafficCollector;
import com.att.aro.core.packetanalysis.pojo.AbstractTraceResult;
import com.att.aro.core.packetanalysis.pojo.HttpRequestResponseInfo;
import com.att.aro.core.packetanalysis.pojo.Session;
import com.att.aro.core.settings.Settings;
import com.att.aro.core.util.GoogleAnalyticsUtil;
import com.att.aro.core.util.IStringParse;
import com.att.aro.core.util.Util;
import com.att.aro.core.videoanalysis.IVideoAnalysisConfigHelper;
import com.att.aro.core.videoanalysis.IVideoEventDataHelper;
import com.att.aro.core.videoanalysis.IVideoTabHelper;
import com.att.aro.core.videoanalysis.impl.VideoSegmentAnalyzer;
import com.att.aro.core.videoanalysis.pojo.Manifest;
import com.att.aro.core.videoanalysis.pojo.Manifest.ContentType;
import com.att.aro.core.videoanalysis.pojo.Manifest.ManifestType;
import com.att.aro.core.videoanalysis.pojo.ManifestCollection;
import com.att.aro.core.videoanalysis.pojo.StreamingVideoData;
import com.att.aro.core.videoanalysis.pojo.VideoStream;
import com.att.aro.core.videoanalysis.pojo.config.VideoAnalysisConfig;

import lombok.Data;

/**
 * Video Streaming Analysis
 */
@Data
public class VideoTrafficCollectorImpl implements IVideoTrafficCollector {
	private static final Logger LOG = LogManager.getLogger(VideoTrafficCollectorImpl.class.getName());

	@Autowired private IFileManager filemanager;
	@Autowired private Settings settings;
	@Autowired private IExternalProcessRunner extrunner;
	@Autowired private IVideoAnalysisConfigHelper voConfigHelper;
	@Autowired private IVideoEventDataHelper voEventDataHelper;
	@Autowired private IVideoTabHelper videoTabHelper;
	@Autowired private IHttpRequestResponseHelper reqhelper;
	@Autowired private IStringParse stringParse;
	@Autowired private VideoStreamConstructor videoStreamConstructor;
	@Autowired private VideoSegmentAnalyzer videoSegmentAnalyzer;
	
	Pattern extensionPattern = Pattern.compile("(\\b[a-zA-Z0-9\\-_\\.]*\\b)(\\.[a-zA-Z0-9]*\\b)");
	private String tracePath;
	private String videoPath;
	private boolean absTimeFlag = false;
	private final String fileVideoSegments = "video_segments";
	private VideoAnalysisConfig vConfig;
	
	@Value("${ga.request.timing.videoAnalysisTimings.title}")
	private String videoAnalysisTitle;
	@Value("${ga.request.timing.analysisCategory.title}")
	private String analysisCategory;

	private VideoStream videoStream;
	private ManifestCollection manifestCollection = null;

	private StreamingVideoData streamingVideoData;

	private List<HttpRequestResponseInfo> segmentRequests = new ArrayList<>();

	private Manifest manifest;
	private double trackManifestTimeStamp;
	
	/**
	 * KEY: video segment request
	 * DATA: timestamp of last child manifest
	 */
	private Map<String, Double> manifestReqMap = new HashMap<>();

	private Manifest trackManifest;

	private boolean audioEnabled = true;
	
	@Override
	public StreamingVideoData collect(AbstractTraceResult result, List<Session> sessionlist, SortedMap<Double, HttpRequestResponseInfo> requestMap) {
		
		long analysisStartTime = System.currentTimeMillis();
		
		tracePath = result.getTraceDirectory() + Util.FILE_SEPARATOR;
		LOG.info("VideoAnalysis for :" + tracePath);
		
		init();
		
		videoPath = tracePath + fileVideoSegments + Util.FILE_SEPARATOR;
		if (!filemanager.directoryExist(videoPath)) {
			filemanager.mkDir(videoPath);
		} else {
			filemanager.directoryDeleteInnerFiles(videoPath);
		}
		
		streamingVideoData = new StreamingVideoData(result.getTraceDirectory());
		videoStreamConstructor.setStreamingVideoData(streamingVideoData);

		processRequests(requestMap);
		
		processSegments();

		streamingVideoData.scanVideoStreams();
		
		GoogleAnalyticsUtil.getGoogleAnalyticsInstance().sendAnalyticsTimings(videoAnalysisTitle, System.currentTimeMillis() - analysisStartTime, analysisCategory);

		LOG.debug("\nFinal results: \n" + streamingVideoData);
		LOG.debug("\nTraffic HEALTH:\n" + displayFailedRequests());
		LOG.info("\n**** FIN **** " + tracePath + "\n\n");
		
		videoSegmentAnalyzer.process(result, streamingVideoData);
		
		return streamingVideoData;
	}

	private String displayFailedRequests() {
		StringBuilder strbldr = new StringBuilder();
		double failed = streamingVideoData.getFailedRequestMap().size();
		double succeeded = streamingVideoData.getRequestMap().size();
		if (failed!=0 || succeeded!=0) {
			strbldr.append(String.format("Video network traffic health : %2.0f%% Requests succeeded", succeeded * 100 / (succeeded + failed)));
		}
		if (failed != 0) {
			strbldr.append("\nFailed requests list by timestamp");
			streamingVideoData.getFailedRequestMap().entrySet().stream().forEach(x -> {
				strbldr.append(String.format("\n%10.3f %s", x.getKey(), x.getValue()));
			});
		}
		return strbldr.toString();
	}

	public void init() {
		LOG.info("\n**** INITIALIZED **** " + tracePath);
		videoStreamConstructor.init();
		manifestReqMap = new HashMap<>();
		clearData();
	}

	public void processRequests(SortedMap<Double, HttpRequestResponseInfo> requestMap) {
		for (HttpRequestResponseInfo req : requestMap.values()) {
			LOG.info(req.toString());
			
			if (req.getAssocReqResp() == null) {
				LOG.info("NO Content (skipped):" + req.getTimeStamp() + ": " + req.getObjUri());
				videoStreamConstructor.addFailedRequest(req);
				continue;
			}
			
			String fname = videoStreamConstructor.extractFullNameFromRRInfo(req);
			LOG.info("oName :" + req.getObjNameWithoutParams() + "\theader :" + req.getAllHeaders() + "\tresponse :" + req.getAssocReqResp().getAllHeaders());

			String extn = videoStreamConstructor.extractExtensionFromRequest(req);
			if (extn == null) {
				extn = fname;
			}
			if (fname.contains("_audio_")) {
				extn = "audio";
			}
			
			// catch offset filename, usually before a byte range, example: 4951221a-709b-4cb9-8f52-7bd7abb4b5c9_v5.ts/range/340280-740719
			if (fname.contains(".ts\\/")) {
				extn = ".ts";
			}
			if (fname.contains("aac\\/")) {
				extn = ".aac";
			}
			
			switch (extn) {
			
			// DASH - Amazon, Hulu, Netflix, Fullscreen
			case ".mpd": // Dash Manifest
				processManifestDASH(req);
				break;
				
			case ".m3u8": // HLS Manifest, iOS, DTV
				processManifestHLS(req);
				break;

			case ".m4a": // 69169728
			case ".aac": // audio
			case "audio": // audio
				if (audioEnabled) {
					segmentRequests.add(req);
					LOG.info("\taudio segment: " + trackManifestTimeStamp+": "+req.getObjNameWithoutParams());
				}
				break;
			case ".dash":
			case ".mp2t":
			case ".mp4": // Dash/MPEG
			case ".m4v":
			case ".m4i":
			case ".ts": // HLS video
				segmentRequests.add(req);
				LOG.info("\tsegment: " + trackManifestTimeStamp+": "+req.getObjNameWithoutParams());
				manifestReqMap.put(req.getObjNameWithoutParams(), trackManifestTimeStamp);
				break;
				
			case ".ism": // SSM
				LOG.debug("skipping SmoothStreamingMedia :" + fname);
				break;
			case ".vtt":
				LOG.debug("skipping closed caption :" + fname);
				break;
				
			default:// items found here may need to be handled OR ignored as the above
				LOG.info("skipped :" + fname);
				break;
			}
		}
	}

	public void processManifestDASH(HttpRequestResponseInfo req) {
		if ((manifest = videoStreamConstructor.extractManifestDash(streamingVideoData, req)) != null) {
			if (manifest.getManifestType().equals(ManifestType.CHILD) && manifest.getContentType().equals(ContentType.VIDEO)) {
				trackManifestTimeStamp = req.getTimeStamp();
				trackManifest = manifest;
			}
			LOG.info("extracted :" + manifest.getVideoName());
		} else {
			LOG.error("Failed to extract manifest:" + req);
		}
	}
	
	public void processManifestHLS(HttpRequestResponseInfo req) {
		if ((manifest = videoStreamConstructor.extractManifestHLS(streamingVideoData, req)) != null) {
			if (manifest.getManifestType().equals(ManifestType.CHILD)
					&& (manifest.getContentType().equals(ContentType.VIDEO) || manifest.getContentType().equals(ContentType.MUXED))) {
				LOG.info("childManifest videoName:" + manifest.getVideoName() + ", timestamp: " + manifest.getRequestTime());
				trackManifestTimeStamp = req.getTimeStamp();
				trackManifest = manifest;
			} else if (manifest.getManifestType().equals(ManifestType.MASTER)) {
				LOG.info("Manifest videoName:" + manifest.getVideoName() + ", timestamp: " + manifest.getRequestTime());
			}
			LOG.info("extract :" + manifest.getVideoName());
			LOG.info(manifest.displayContent(true, 20));
		}
	}
	
	public void processSegments() {
		Double trackManifestTimestamp = null;
		LOG.info("\n>>>>>>>>>> segmentRequests: " + segmentRequests);
		for (HttpRequestResponseInfo req : segmentRequests) {
			LOG.info("\n>>>>>>>>>> Segment: " + req.getObjNameWithoutParams());
			trackManifestTimestamp = manifestReqMap.get(req.getObjNameWithoutParams());
			videoStreamConstructor.extractVideo(streamingVideoData, req, trackManifestTimestamp);
		}
	}
	
	@Override
	public StreamingVideoData clearData() {
		this.segmentRequests.clear();
		if (streamingVideoData != null ) {
			streamingVideoData.getStreamingVideoCompiled().clear();
		}
		videoTabHelper.resetRequestMapList();
		streamingVideoData = new StreamingVideoData("");
		return streamingVideoData;
	}
}
