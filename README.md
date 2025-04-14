Apple Maps WV
========
Apple Maps WV is a WebView wrapper for using Apple Maps without exposing your device.

Features
--------
- Clears private data on close
- Blocks access to Apple trackers
- Restricts all network requests to HTTPS
- Allows toggling of location permission
- Blocks multi-window and window.open()
- Safebrowsing API is turned off

Downsides
---------
- No cache is used and resources are always loaded from network, so loads may be slow in poor network conditions
- Many links will not prompt for the user to open due to Apple Maps not using https (e.g. `http://www.tripadvisor.com/AppleMapsAction` and `https://yelp.com/apple_maps_action`)

Credits
-------
Forked from https://github.com/woheller69/maps, which was forked from https://github.com/Divested-Mobile/maps.

