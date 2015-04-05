package com.yshenWowza3.HttpProviders;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.wowza.wms.http.HTTPProvider2Base;
import com.wowza.wms.http.IHTTPRequest;
import com.wowza.wms.http.IHTTPResponse;
import com.wowza.wms.vhost.IVHost;



public class CupertinoServer3 extends HTTPProvider2Base {
	
	public void onHTTPRequest(IVHost vhost, IHTTPRequest req, IHTTPResponse resp) {
		if (!doHTTPAuthentication(vhost, req, resp))
			return;
		String requestURL = req.getRequestURI();
		String retStr = null;
		try {
			if (requestURL.toLowerCase().indexOf("initsystem") != -1) {
				String queryParameter = req.getQueryString();
				String myPatternStr = "ServerID=(\\d{1})&CloudFrontDNS=([\\w\\.]+)$";
				Pattern myPattern = Pattern.compile(myPatternStr);
				Matcher matcher = myPattern.matcher(queryParameter);
				String ServerID = matcher.group(1);
				String CloudFrontDNS = matcher.group(2);
				MasterPlaylistCollection.ServerID = ServerID;
				MasterPlaylistCollection.CloudFrontDNS = CloudFrontDNS;
				MediaPlaylistCollection.CloudFrontDNS = CloudFrontDNS;
				retStr = "system init successfully!\nServerID = " + ServerID + "CloudFrontDNS= " + CloudFrontDNS + '\n';
			} else if (requestURL.toLowerCase().endsWith("masterlist.m3u8")) {
				String localMasterPlaylist = MasterPlaylistCollection.GetMasterPlaylist(requestURL);
				retStr = localMasterPlaylist;
			} else if (requestURL.toLowerCase().indexOf("chunklist_b") != -1) {
				String mediaPlaylist = MediaPlaylistCollection.GetMediaPlaylists(requestURL);
				retStr = mediaPlaylist;
			}
			OutputStream out = resp.getOutputStream();
			out.write(retStr.getBytes());
			resp.setHeader("Cache-Control", "private, no-cache, no-store");
			resp.setHeader("Content-Type", "application/vnd.apple.mpegurl");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
}

class MasterPlaylistCollection {
	static HashMap<String, String> originalMasterPlaylists = new HashMap<>();
	static HashMap<String, ArrayList<String>> masterPlaylistSet = new HashMap<String, ArrayList<String> >();
	static String ServerID = null;
	static String CloudFrontDNS = null;
	static double[] probabilityDistribution = new double[] {0.1, 0.3, 0.6}; 
	static int[] delay = new int[]{0, 1, 2};
	
	static void InitMasterPlaylist(String AppName, String StreamName) throws IOException {
		if (originalMasterPlaylists.containsKey(StreamName)) {
			return; 
		}
		synchronized (MasterPlaylistCollection.class)
		{
			if(originalMasterPlaylists.containsKey(StreamName)){
				return;
			}
			String masterPlaylistRequest = String.format("http://localhost:1935/%s/smil:%s.smil/playlist.m3u8", AppName, StreamName);
			String tmpMasterPlaylist = MyHttpClient.Get(masterPlaylistRequest).get("content");
			String[] lines = tmpMasterPlaylist.split("\\r?\\n");
			StringBuilder builder = new StringBuilder();
			StringBuilder[] delayVersionBuilder = new StringBuilder[probabilityDistribution.length];
			for (int k = 0; k<delayVersionBuilder.length; k++) {
				delayVersionBuilder[k] = new StringBuilder();
			}
			for (int k = 0; k < lines.length; k++) {
				lines[k] = lines[k];//.replaceFirst("chunklist", "mediaplaylist").replace("m3u8","m3u8");
				builder.append(lines[k] + "\n");
				if (lines[k].startsWith("chunklist_b")) {
					for (int j = 0; j != probabilityDistribution.length; j++){
						delayVersionBuilder[j].append("http://" + CloudFrontDNS + String.format("/%s/%s/Group%s/", AppName,
								StreamName, ServerID) + lines[k] + String.format("?Delay=%d\n", delay[j]));
					}
				} else {
					for (int j = 0; j != probabilityDistribution.length; j++){
						delayVersionBuilder[j].append(lines[k] + "\n");
					}
				}
			}
			originalMasterPlaylists.put(StreamName, builder.toString() );// = builder.toString();
			ArrayList<String> tmpList = new ArrayList<>();
			for (int k = 0; k != probabilityDistribution.length; k++) {
				tmpList.add(delayVersionBuilder[k].toString());
			}
			masterPlaylistSet.put(StreamName, tmpList);
		}
	}
	
	
	static String GetMasterPlaylist(String requestURL) throws IOException {
		//http://WowzaGroupyshenGroupID/AppName/$StreamName/masterlist.m3u8;
		String myPatternStr = "^\\/?(\\w+)\\/(\\w+)\\/masterlist\\.m3u8";
		Pattern myPattern = Pattern.compile(myPatternStr);
		Matcher matcher = myPattern.matcher(requestURL);
		String AppName = matcher.group(1);
		String StreamName = matcher.group(2);

		InitMasterPlaylist(AppName, StreamName);
		double randNum = Math.random();
		double accumulateProbability = 0;
		for (int k = 0; k != probabilityDistribution.length; k++){
			accumulateProbability += probabilityDistribution[k];
			if (randNum <= accumulateProbability) {
				return masterPlaylistSet.get(StreamName).get(k);
			}
		}
		return null;
	}
	
}

class MediaPlaylistCollection {
	static long lastUpdateTime = -1;
	static int updatePeriod = 2000;
	static double[] probabilityDistribution = new double[] {0.1, 0.3, 0.6}; 
	static int[] delay = new int[]{0, 1, 2};
	static HashMap<String, HashMap<String, String> > originalMediaPlaylistsHashTable = new HashMap<>();  //  [rendition]
	static int mediaPlaylistLength = 3;
	static HashMap<String, HashMap<String, ArrayList<String> > >mediaPlaylistSetHashTable = new HashMap<>();  //  [rendition][delay]
	static final ReadWriteLock timeStampLock = new ReentrantReadWriteLock();
	static final ReadWriteLock mediaPlaylistsLock = new ReentrantReadWriteLock();
	static HashMap<String, String> masterPlaylist = new HashMap<>();
	static String CloudFrontDNS = null;
	static void Update(String appName, String streamName, String groupID, String RenditionInRequest, int DelayInRequest) throws IOException {
		if (masterPlaylist.containsKey(streamName)) {
			synchronized (MasterPlaylistCollection.class) {
				if (masterPlaylist.containsKey(streamName)){
					String masterPlaylistRequest = String.format("http://localhost:1935/%s/smil:%s.smil/playlist.m3u8", appName, streamName);
					masterPlaylist.put(streamName, MyHttpClient.Get(masterPlaylistRequest).get("content"));
               }
			}
		}
		String[] lines = masterPlaylist.get(streamName).split("\\r?\\n");
		HashMap<String, String> oneStreamOriginalMediaPlaylistsHashTable = new HashMap<>(); //store the media playlist for this stream only; key is the rendition
		HashMap<String, ArrayList<String> > oneStreamMediaPlaylistSetHashTable = new HashMap<>();
		for (int k = 0; k < lines.length; k++) {
			if (lines[k].startsWith("#EXT-X-STREAM-INF")) {
				String rendition = lines[k+1].substring(lines[k+1].lastIndexOf("_b") + 1, lines[k+1].lastIndexOf(".m3u8"));
				String mediaPlaylistRequest =  String.format("http://localhost:1935/%s/%s/chunklist.m3u8", appName, streamName);
				String originalMediaPlaylist = MyHttpClient.Get(mediaPlaylistRequest).get("content");
				originalMediaPlaylist = originalMediaPlaylist.replaceAll("media", "media_" + rendition);
				oneStreamOriginalMediaPlaylistsHashTable.put(rendition, originalMediaPlaylist);
				String[] mediaPlaylistLines = originalMediaPlaylist.split("\\r?\\n");
				String headPart = "";
				ArrayList<String[]> bodyPartLines = new ArrayList<>();
				ArrayList<String> delaySet = new ArrayList<>();
				int j = 0;
				for (j = 0; j != mediaPlaylistLines.length; j++){
					if (mediaPlaylistLines[j].startsWith("#EXTINF") == false) {
						headPart += mediaPlaylistLines[j] + "\n";
					} else {
						break;
					}
				}
				for (int d = 0; d < delay.length; d++) {
					bodyPartLines.add(Arrays.copyOfRange(mediaPlaylistLines, j +
							delay[delay.length - 1 - d] * 2, j + delay[delay.length - 1 - d] * 2 + mediaPlaylistLength * 2));
					String tmpStr = headPart;
					for (int i = 0; i < bodyPartLines.get(d).length; i += 1) {
						if (bodyPartLines.get(d)[i].startsWith("#EXTINF")) {
							tmpStr += bodyPartLines.get(d)[i] + "\n";
						} else {
							tmpStr += String.format("http://%s/%s/smil:%s.smil/Group%s/%s",
									CloudFrontDNS, appName, streamName, groupID, bodyPartLines.get(d)[i] + '\n');  
						}
					}
					delaySet.add(tmpStr);
				}
				oneStreamMediaPlaylistSetHashTable.put(rendition, delaySet);
			}
		}
		originalMediaPlaylistsHashTable.put(streamName, oneStreamOriginalMediaPlaylistsHashTable);
		mediaPlaylistSetHashTable.put(streamName, oneStreamMediaPlaylistSetHashTable);
		lastUpdateTime = System.currentTimeMillis();
	}	
	
	static String GetMediaPlaylists(String mediaPlaylistURL) throws IOException {
		//proxy_pass http://WowzaGroup$GroupID/AppName/$StreamName/Group$GroupID/chunklist_$Bandwidth.m3u8?Delay=$Delay;
		String myPatternStr = "\\/?(\\w+)\\/(\\w+)\\/Group(\\d{1})\\/chunklist_(b\\d+)\\.m3u8\\?Delay=(\\d{1})";
		Pattern myPattern = Pattern.compile(myPatternStr);
		Matcher matcher = myPattern.matcher(mediaPlaylistURL); 
		String appName = matcher.group(1);
		String streamName = matcher.group(2);
		String groupID = matcher.group(3);
		String renditionInRequest = matcher.group(4);
		int delayInRequest = Integer.parseInt(matcher.group(5));
		long currentTime = System.currentTimeMillis();
		timeStampLock.readLock().lock();
		if (currentTime - lastUpdateTime >= updatePeriod) {
			timeStampLock.readLock().unlock();
			timeStampLock.writeLock().lock();
			if (currentTime - lastUpdateTime >= updatePeriod) {
				Update(appName, streamName, groupID, renditionInRequest, delayInRequest);
			}
			String retMediaPlaylist = mediaPlaylistSetHashTable.get(streamName).get(renditionInRequest).get(delayInRequest); 
			timeStampLock.writeLock().unlock();
			return retMediaPlaylist;
		} else {
			String retMediaPlaylist = mediaPlaylistSetHashTable.get(streamName).get(renditionInRequest).get(delayInRequest); 
			timeStampLock.readLock().unlock();
			return retMediaPlaylist;
		}
		
	}
	
}

class MyHttpClient {
	static HashMap<String, String> Get(String requestStr) throws IOException {
		HashMap<String, String> response = new HashMap<>();
        URL url = new URL(requestStr);
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        
        response.put("Response-Code", Integer.toString(connection.getResponseCode()));
        for (String key: connection.getHeaderFields().keySet()){
        	response.put(key, connection.getHeaderField(key));
       	}
        if (response.get("Response-Code").equals("200")){
        	StringBuilder content = new StringBuilder();
        	InputStream is = connection.getInputStream();
			int data = is.read();
			while(data != -1){
				char theData = (char) (data);
				content.append(theData);
				data = is.read();
			}
			response.put("content", content.toString());
			
        }
		return response;
    }
}


				
				