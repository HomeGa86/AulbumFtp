An android app to view pictures and videos on a ftp or sftp server, it auto downloads all pictures and videos from ftp server or sftp server and create small size local pictures to save storage, when you click a local picture or a video, it auto downloads the original picture from the ftp or sftp server
This app's feature is to simulate a cloud photo of phones, but it is free. You can setup your ftp/sftp server with many free choices, sftp server is recommended because it's secure and it supports resuming download. This app only downloads pictures  or videos from a server, it does not upload any file to a server.

Why do I write this app?
   Because I have lots of photoes and videos (> 200G) stored on computer's hard disks, but sometimes I would like to view them, it's not convenient to use a computer. And I know that there are some other apps which have the similar feature, but most of those apps require the user to deploy a "heavy" server which costs much electricity and money. So a ftp/sftp server can be deployed to small devices like Android TV box (which is my server) or some other small devices which costs much less electricity. An android device's typical voltage is 5v, flow is around 1A or 2A, so it's just 5W or 10W, very less than a 100W or 200W computer.

1. Information Security
   This app does not need to access your phone's folders (e.g. the photo folders or any other folders).
   It will not ask you for permission to access your storage, so it's safe, don't worry to use it.
   This app only write/read data from its own folder which is the app's specific folder, and read data from ftp/sftp server, so it's very safe.
2. A downloaded file will not be downloaded again
   A file is identified by its ftp full path excluding its host name and port (e.g. a file's id is /rootfolder/subfolder1/subfolder2/photo.jpg)
   That means, if the folder structure doesn't change, the downloaded file will not be downloaded again even if you change a server host name or port.
3. Below file types are supported
   .jpg, .jpeg, .png, .gif, .bmp, .webp, .mp4, .mkv, .mov, .avi, .3gp
4. Pictures and Videos will be downloaded, and a very small size thumbnail picture will be generated and stored on the phone. Video will not be store on your phone.
   Original picture or video will be downloaded only when you click it in the app, and the original picture or video files are stored in a app sepecifc cache folder, the cache will be cleared when the app is closed.
   If the cache is not cleared when the app is closed, don't worry, most Android systems will automatically clear the cache when it thinks it's too large.
5. Android 11 and Android 16 are tested
6. The app will try to extract the capture date&time from the original pictures and videos, if the original pictures and videos don't have a capture date&time, the app will try to extract it from the file name. Capture date&time will be used to sort the pictures and videos in the app.
7. If all the pictures and videos have been downloaed successfully, then the app will not try to download or list the files from the server again within 10 days. The purpose of this feature is to save network access of the phone and it assumes the photoes and videos on the server don't change frequently.
