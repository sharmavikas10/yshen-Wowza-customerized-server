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

import com.wowza.wms.http.HTTPProvider2Base;
import com.wowza.wms.http.IHTTPRequest;
import com.wowza.wms.http.IHTTPResponse;
import com.wowza.wms.vhost.IVHost;



public class CupertinoServer3 extends HTTPProvider2Base {
	//static String Server_ID = null;
	
	public void onHTTPRequest(IVHost vhost, IHTTPRequest req, IHTTPResponse resp) {
		if (!doHTTPAuthentication(vhost, req, resp))
			return;
		String requestURL = req.getRequestURI();
		String retStr = null;
		try {
			if (requestURL.toLowerCase().indexOf("initsystem") != -1) {
				String queryParameter = req.getQueryString();
				queryParameter = queryParameter.substring(queryParameter.indexOf('=') + 1);
				MasterPlaylistCollection.serverIP = queryParameter;
				retStr = "system init successfully!\nServerID = " + queryParameter + '\n';
			} else if (requestURL.toLowerCase().endsWith("masterlist.m3u8")) {
				String localMasterPlaylist = MasterPlaylistCollection.GetMasterPlaylist();
				retStr = localMasterPlaylist;
			} else if (requestURL.toLowerCase().indexOf("chunklist_b") != -1) {
				String mediaPlaylist = MediaPlaylistCollection.GetMediaPlaylists(requestURL);
				retStr = mediaPlaylist;
			}
			OutputStream out = resp.getOutputStream();
			out.write(retStr.getBytes());
			resp.setHeader("Cache-Control", "private, no-cache, no-store");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
}

class MasterPlaylistCollection {
	static String originalMasterPlaylist = null;
	static String masterPlaylistRequest = "http://54.245.88.133:1935/WowzaLiveCFApp1/smil:rtmpElemental.smil/playlist.m3u8";
	static List<String> masterPlaylistSet = new ArrayList<>();
	static String serverIP = null;
	static double[] probabilityDistribution = new double[] {0.1, 0.3, 0.6}; 
	static int[] delay = new int[]{0, 1, 2};
	
	static void InitMasterPlaylist() throws IOException {
		if (originalMasterPlaylist !=null){
			return;
		}
		synchronized (MasterPlaylistCollection.class)
		{
			if(originalMasterPlaylist !=null){
				return;
			}
			String tmpMasterPlaylist = MyHttpClient.Get(masterPlaylistRequest).get("content");
			String[] lines = tmpMasterPlaylist.split("\\r?\\n");
			StringBuilder builder = new StringBuilder();
			StringBuilder[] delayVersionBuilder = new StringBuilder[probabilityDistribution.length];
			for (int k = 0; k<delayVersionBuilder.length; k++) {
				delayVersionBuilder[k] = new StringBuilder();
			}
			for (int k = 0; k < lines.length; k++) {
				lines[k] = lines[k];//.replaceFirst("chunklist", "mediaplaylist").replace("m3u8","m3u8");
				builder.append(lines[k]+'\n');
				if (lines[k].startsWith("chunklist_b")) {
					for (int j = 0; j != probabilityDistribution.length; j++){
						delayVersionBuilder[j].append(lines[k] + String.format("?delay=%d&Server_ID=%s\n", delay[j], serverIP));
					}
				} else {
					for (int j = 0; j != probabilityDistribution.length; j++){
						delayVersionBuilder[j].append(lines[k] + '\n');
					}
				}
			}
			originalMasterPlaylist = builder.toString();
			for (int k = 0; k != probabilityDistribution.length; k++) {
				masterPlaylistSet.add(delayVersionBuilder[k].toString());
			}
		}
	}
	
	static String GetMasterPlaylist(int index) throws IOException {
		InitMasterPlaylist();
		return masterPlaylistSet.get(index);
	}
	
	static String GetMasterPlaylist() throws IOException {
		InitMasterPlaylist();
		double randNum = Math.random();
		double accumulateProbability = 0;
		for (int k = 0; k != probabilityDistribution.length; k++){
			accumulateProbability += probabilityDistribution[k];
			if (randNum <= accumulateProbability) {
				return masterPlaylistSet.get(k);
			}
		}
		return null;
	}
	
	static String GetOriginalMasterPlaylist() throws IOException {
		InitMasterPlaylist();
		return originalMasterPlaylist;
	}
}

class MediaPlaylistCollection {
	static long lastUpdateTime = -1;
	static int updatePeriod = 1000;
	static double[] probabilityDistribution = new double[] {0.1, 0.3, 0.6}; 
	static int[] delay = new int[]{0, 1, 2};
	//static String segmentURLPrefix = "http://dwfjco7l53l6c.cloudfront.net/WowzaLiveCFApp1/smil:rtmpElemental.smil/";
	static String masterPlaylistRequest = "http://54.245.88.133:1935/WowzaLiveCFApp1/smil:rtmpElemental.smil/playlist.m3u8";
	//static String mediaPlaylistRequestTemplate = "http://localhost:1935/WowzaLiveCFApp1/smil:rtmpElemental.smil/chunklist_%s.m3u8";
	static List<String> originalMediaPlaylists = null;  //  [rendition]
	static HashMap<String, String> originalMediaPlaylistsHashTable = null;  //  [rendition]
	static int mediaPlaylistLength = 3;
	static ArrayList<ArrayList<String> > mediaPlaylistSet = null;  //  [rendition][delay]
	static HashMap<String, ArrayList<String> > mediaPlaylistSetHashTable = null;  //  [rendition][delay]
	static final ReadWriteLock timeStampLock = new ReentrantReadWriteLock();
	static final ReadWriteLock mediaPlaylistsLock = new ReentrantReadWriteLock();
	static String masterPlaylist = null;
	
	static void Update() throws IOException {
		if (masterPlaylist == null) {
			masterPlaylist = MyHttpClient.Get(masterPlaylistRequest).get("content");
		}
		String[] lines = masterPlaylist.split("\\r?\\n");
		originalMediaPlaylists = new ArrayList<>();
		mediaPlaylistSet = new ArrayList<>();
		originalMediaPlaylistsHashTable = new HashMap<>();
		mediaPlaylistSetHashTable = new HashMap<>();
		for (int k = 0; k < lines.length; k++) {
			if (lines[k].startsWith("#EXT-X-STREAM-INF")) {
				String rendition = lines[k+1].substring(lines[k+1].lastIndexOf("_b") + 1, lines[k+1].lastIndexOf(".m3u8"));
				//String mediaPlaylistRequest = String.format(mediaPlaylistRequestTemplate, rendition);
				String mediaPlaylistRequest =  "http://54.245.88.133:1935/WowzaLiveCFApp1/rtmpElemental/chunklist.m3u8";
				String originalMediaPlaylist = MyHttpClient.Get(mediaPlaylistRequest).get("content");
				originalMediaPlaylist = originalMediaPlaylist.replaceAll("media", "media_" + rendition);
				originalMediaPlaylists.add(originalMediaPlaylist);
				originalMediaPlaylistsHashTable.put(rendition, originalMediaPlaylist);
				String[] mediaPlaylistLines = originalMediaPlaylist.split("\\r?\\n");
				String headPart = "";
				ArrayList<String[]> bodyPartLines = new ArrayList<>();
				ArrayList<String> delaySet = new ArrayList<>();
				int j = 0;
				for (j = 0; j != mediaPlaylistLines.length; j++){
					if (mediaPlaylistLines[j].startsWith("#EXTINF") == false) {
						headPart += mediaPlaylistLines[j] + '\n';
					} else {
						break;
					}
				}
				for (int d = 0; d < delay.length; d++) {
					bodyPartLines.add(Arrays.copyOfRange(mediaPlaylistLines, j + delay[delay.length - 1 - d] * 2, j + delay[delay.length - 1 - d] * 2 + mediaPlaylistLength * 2));
					String tmpStr = headPart;
					for (int i = 0; i < bodyPartLines.get(d).length; i += 1) {
						tmpStr += bodyPartLines.get(d)[i] + '\n';
					}
					delaySet.add(tmpStr);
				}
				mediaPlaylistSet.add(delaySet);	
				mediaPlaylistSetHashTable.put(rendition, delaySet);
			}
		}
		lastUpdateTime = System.currentTimeMillis();
	}	
	
	static String GetMediaPlaylists(String mediaPlaylistURL) throws IOException {
		int delayInRequest = Integer.parseInt(mediaPlaylistURL.substring(mediaPlaylistURL.indexOf("delay=") + 6, mediaPlaylistURL.lastIndexOf('&')));
		String rendition = mediaPlaylistURL.substring(mediaPlaylistURL.lastIndexOf("chunklist_b") + 10, mediaPlaylistURL.lastIndexOf(".m3u8"));
		String serverid = mediaPlaylistURL.substring(mediaPlaylistURL.lastIndexOf("Server_ID=") + 10);
		long currentTime = System.currentTimeMillis();
		timeStampLock.readLock().lock();
		if (currentTime - lastUpdateTime >= updatePeriod) {
			timeStampLock.readLock().unlock();
			timeStampLock.writeLock().lock();
			if (currentTime - lastUpdateTime >= updatePeriod) {
				Update();
			}
			String retMediaPlaylist = mediaPlaylistSetHashTable.get(rendition).get(delayInRequest); 
			timeStampLock.writeLock().unlock();
			retMediaPlaylist = AttachServerID(retMediaPlaylist, serverid);
			return retMediaPlaylist;
		} else {
			String retMediaPlaylist = mediaPlaylistSetHashTable.get(rendition).get(delayInRequest); 
			timeStampLock.readLock().unlock();
			retMediaPlaylist = AttachServerID(retMediaPlaylist, serverid);
			return retMediaPlaylist;
		}
		
	}
	
	static String AttachServerID(String mediaPlaylist, String serverid) {
		String[] lines = mediaPlaylist.split("\\r?\\n");
		StringBuilder sb = new StringBuilder();
		for (int k = 0; k != lines.length; k++) {
			sb.append(lines[k]);
			if (lines[k].startsWith("media_b")) {
				sb.append("?Server_ID=" + serverid);
			}
			sb.append('\n');
		}
		return sb.toString();
	}
}

class MyHttpClient {
	static HashMap<String, String> Get(String requestStr) throws IOException {
		HashMap<String, String> response = new HashMap<>();
        URL url = new URL(requestStr);
       // HttpsURLConnection connection  = (HttpsURLConnection) url.openConnection();
        
      //  URL url = new URL("https://www.verisign.com");
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


				
				