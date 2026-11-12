# WifiDrop

Copy a large folder from a Mac to an external hard disk, over Wi-Fi, by plugging the disk into an Android phone.

## The problem this solves

I had about 95 GB of personal files on a laptop, mostly photos and videos, and needed to move them to an external hard disk. The laptop could not use the disk directly. Cloud upload was too slow and too expensive for that much data, and there was no other computer with enough free space to act as a stopover.

An Android phone, though, reads and writes the hard disk without trouble. So the phone became the bridge:

```
Mac  ── Wi-Fi ──>  Android phone  ── USB cable ──>  external hard disk
```

WifiDrop is the pair of small programs that does this:

- **A phone app** that receives files over your home Wi-Fi and writes them to a folder you choose on the attached disk.
- **A script for the Mac** that lists the folder you want to send, works out what the disk already has, and sends the rest.

It can be stopped at any point (Wi-Fi drop, flat battery, closed laptop) and started again. Each run compares both sides first and continues from where it stopped, including from the middle of a large file.

> WifiDrop uses ordinary Wi-Fi networking and does not bypass any security control. Only copy files you are allowed to copy.

## What you need

- A Mac. The script uses only tools that come with macOS (`bash`, `curl`, `shasum`, `awk`), so there is nothing to install.
- An Android phone running Android 8 or later that can read and write your hard disk. Check this first in the phone's file manager.
- A hard disk with enough free space, and a cable or adapter to plug it into the phone. A disk formatted as exFAT works well; FAT32 cannot hold any single file larger than 4 GB.
- Both the Mac and the phone on the same Wi-Fi network.

## Step 1: install the phone app

1. On the phone, download [WifiDrop.apk](https://github.com/venugopalarjun/wifidrop/releases/download/latest/WifiDrop.apk).
2. Open the downloaded file and install it. Android asks you to allow installs from your browser, and Play Protect may warn that it does not recognise the app. That is expected for an app that is not from the Play Store.
3. Plug the hard disk into the phone and open WifiDrop.
4. Tap **Choose a folder on the hard disk**, pick or create a folder on the disk, and allow access.
5. Tap **Start receiving**. The app now shows the command to run on the Mac, with the phone's address and a 6-digit code.

## Step 2: send from the Mac

1. Download [wifidrop.sh](https://github.com/venugopalarjun/wifidrop/releases/download/latest/wifidrop.sh). Your browser saves it in Downloads, and it can stay there: where the script sits has nothing to do with which folder gets sent.
2. Open Terminal and run the command the phone shows, with the folder you want to send added at the end:

   ```
   bash ~/Downloads/wifidrop.sh 192.168.1.5 123456 ~/Pictures
   ```

   If you leave the folder off, it sends your whole Downloads folder. The script only reads the folder you name; it does not change or delete anything in it.

3. If macOS asks whether Terminal may access the folder, click Allow.

The script prints what it found, then a progress line:

```
On this Mac:      3253 files, 94.92 GB
Already on disk:  0 complete files, 0 KB
To send now:      3253 files, 94.92 GB
  1.52 of 94.92 GB (1%)   25.2 MB/s   file 2 of 3253
```

The phone shows the same progress.

To keep the Mac awake during a long transfer, open a second Terminal window, run `caffeinate -i`, and leave it open. Keep the laptop lid open.

## If it stops

- **Wi-Fi drops:** the script waits and carries on by itself when the phone is reachable again.
- **Anything else** (the Mac slept, the app was closed, you pressed Ctrl-C): run the same command again. It lists both sides, skips every file that is already complete, continues a half-sent file from where it stopped, and then compares that file's checksum.
- **The phone's battery ran out:** charge it, plug the disk back in, open the app, check that Step 1 still shows your folder, tap **Start receiving**, and run the command again. The phone's address can change after a restart, so read it from the app again.

It is better to stop cleanly than to let the battery die, because a disk that loses power mid-write can end up with a damaged file. Press Ctrl-C on the Mac, tap **Stop receiving**, and eject the disk in the phone's settings before unplugging it.

## Step 3: verify

When sending finishes, the script checks that every file is on the disk with the right size. For a stronger check, run the `verify` command it prints:

```
bash ~/Downloads/wifidrop.sh verify 192.168.1.5 123456
```

This re-reads every file from the hard disk, compares its SHA-256 checksum with the file on the Mac, and re-sends any file that differs. It takes a while because it reads everything twice. Do this before you delete anything from the laptop.

## Tips

**Many small files are slow.** Each file costs a fraction of a second on top of its size, so a folder with 100,000 small files takes hours even if it is only a few gigabytes. Zip such a folder first and send the zip:

```
cd ~/Downloads && zip -r -q "Notes.zip" "Notes"
```

**Leave a folder out.** Put `WIFIDROP_EXCLUDE` in front of the command to skip one top-level folder, for example the one you just zipped. Use it on the `verify` command too.

```
WIFIDROP_EXCLUDE=Notes bash ~/Downloads/wifidrop.sh 192.168.1.5 123456
```

**Send a folder as one archive without using disk space on the Mac.** This packs a folder and streams it straight to the hard disk as a single file. It cannot resume, so if it is interrupted, run it again.

```
cd ~/Documents && COPYFILE_DISABLE=1 tar -czf - . | curl -T - -H "X-Code: 123456" -H "Expect: 100-continue" "http://192.168.1.5:8765/put?path=Documents.tar.gz&offset=0"
```

**Battery.** The disk usually takes the phone's charging port and draws power from the phone. Start with a full battery and use a wireless charger if you have one.

**File names.** Characters that FAT, exFAT and NTFS disks reject (`\ : * ? " < > |`, and a trailing dot or space) are replaced with `_` on the disk.

**Not copied.** Empty folders, `.DS_Store` files, and files whose names contain a tab or line break.

## Safety and limits

- **Use it only on a Wi-Fi network you trust.** Files travel over plain HTTP without encryption. The 6-digit code stops other devices on the network from writing to your disk by accident; it is not strong protection against someone determined.
- **Stop the app when you are done.** While it says "Receiving", anyone on the network who knows the code can write to the chosen folder.
- **Tested on one setup:** a Mac sending to a Samsung Galaxy S23 Ultra with an external hard disk, about 95 GB at roughly 25 MB/s. Other phones should work, but they have not been tried.
- **No warranty.** Keep the originals until `verify` has passed.

## How it works

The phone app runs a small HTTP server on port 8765 and writes through Android's Storage Access Framework, which is what lets an ordinary app write to a USB disk. The Mac script talks to it with `curl`:

| Request | Purpose |
| --- | --- |
| `GET /list` | Every file in the destination folder, with its size |
| `GET /stat?path=` | The size of one file on the disk |
| `PUT /put?path=&offset=` | Write a file, starting at `offset` |
| `GET /hash?path=` | SHA-256 of a file, read back from the disk |

Every request must carry the code in an `X-Code` header. To resume, the script asks for the list, compares it with the Mac's list, and for a partly written file sends only the bytes after the size already on the disk.

## Build it yourself

The app is plain Java with no dependencies beyond the Android SDK.

```
gradle :app:assembleDebug
```

This needs JDK 17, Gradle 8.7 or later, and the Android SDK (platform 34). The APK lands in `app/build/outputs/apk/debug/`. Every push to `main` is built by GitHub Actions and published as the `latest` release.

The repository includes its signing key (`signing.jks`) on purpose, so that anyone's build installs over an earlier one. Like Android's standard debug key, it is public and proves nothing about who built an APK. Install only builds you got from a source you trust.

The transfer logic can be tested without a phone: `test/` holds a stand-in for the disk that runs the same server on any computer with Java.

## License

MIT. See [LICENSE](LICENSE).
