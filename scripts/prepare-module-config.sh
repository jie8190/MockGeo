#!/system/bin/sh
set -eu
# Only this application's new configuration directory is changed.
mkdir -p /data/misc/mockgeo
if [ ! -f /data/misc/mockgeo/exempt.txt ]; then
    if [ -f /data/local/tmp/mockgeo_exempt.txt ]; then
        cat /data/local/tmp/mockgeo_exempt.txt > /data/misc/mockgeo/exempt.txt
    else
        touch /data/misc/mockgeo/exempt.txt
    fi
fi
chown 1000:1000 /data/misc/mockgeo /data/misc/mockgeo/exempt.txt
chmod 750 /data/misc/mockgeo
chmod 640 /data/misc/mockgeo/exempt.txt
chcon u:object_r:system_data_file:s0 /data/misc/mockgeo /data/misc/mockgeo/exempt.txt
echo 'MockGeo configuration ready (existing contents preserved).'
