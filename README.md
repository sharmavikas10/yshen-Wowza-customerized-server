# yshen-Wowza-customerized-server
A wowza httpprovider that generates customized playlists. 

Wowza server is really powerful in terms of VOD or live streaming. Most work can be done through editing configuration files under /conf directory. However, sometimes we want to make the live streaming more flexible (e.g. creating customerized playlists). 
Wowza user guide doesn't tell us how to modifying the format or content of playlists. I guess we cannot doing this. So I wrote this Httprovider myself. Basically, when a client http request for playlist comes in, CupertinoServer.class first checks the request type: whether it's a system init command, a request for master playlist, or a request for media playlist (chunk list)? Then if it's for master playlist, a localhost call for master playlist is sent to localhost Wowza server and a format-fixed master playlist is returned. Modification is done on this master playlist and a customerized playlist is returned. Media playlist is customerized similarly. 
For sake of increasing hit rate of Cloudfront (you will use it, right?), incoming requests are assigned with a "delay" tag. Then according to this delay tag, different media playlists will be returned. 
Change wowza_dir/conf/VHost.xml: add this httpprovider to VHost.xml and edit the request filter to "*.m3u8"
change wowza_dir/conf/HTTPStreamers.xml: if don't have one udner conf/ folder, download one from Internet. edit the Cupertino property "requestfilters" to <RequestFilters>*playlist.m3u8|*chunklist.m3u8|.....</RequestFilters>

Enjoy it! :)
