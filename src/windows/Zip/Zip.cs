using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Windows.Data.Json;
using Windows.Foundation;
using Windows.Storage;

namespace Zip
{
    public static class Zip
    {
        private static async Task<StorageFolder> GetOrCreateFolderAtUrlAsync(string url)
        {
            if (url == "ms-appdata:///local/")
                return ApplicationData.Current.LocalFolder;

            var inputUrl = new Uri(url);
            var currentPath = inputUrl.AbsolutePath;

            // Repair AbsolutePath if necessary
            while (currentPath.Contains("//"))
                currentPath = currentPath.Replace("//", "/");

            currentPath = currentPath.TrimEnd('/');

            var segments = currentPath.Split('/');
            if (segments.Length == 0)
                return null;

            // If the URI has a scheme, we probably want triple slashes
            var currentUrl = currentPath + "/";
            if (!string.IsNullOrEmpty(inputUrl.Scheme))
                currentUrl = $"{inputUrl.Scheme}://{currentUrl}";

            try
            {
                return await StorageFolder.GetFolderFromPathAsync(currentUrl);
            }
            catch
            {
                var parent = await GetOrCreateFolderAtUrlAsync($"{inputUrl.Scheme}://{string.Join("/", segments.Take(segments.Length - 1))}/");
                return await parent.CreateFolderAsync(segments.Last(), CreationCollisionOption.OpenIfExists);
            }
        }

        private static async Task<IStorageItem> CreatePathAsync(this StorageFolder folder, string fileLocation, CreationCollisionOption fileCollisionOption, CreationCollisionOption folderCollisionOption)
        {
            var localFilePath = fileLocation;
            if (localFilePath.Length > 0 && (localFilePath[0] == '/' || localFilePath[0] == '\\'))
            {
                localFilePath = localFilePath.Remove(0, 1);
            }

            if (localFilePath.Length == 0)
            {
                return folder;
            }

            var separatorIndex = localFilePath.IndexOfAny(new[] { '/', '\\' });
            if (separatorIndex == -1)
            {
                return await folder.CreateFileAsync(localFilePath, fileCollisionOption);
            }
            else
            {
                var folderName = localFilePath.Substring(0, separatorIndex);
                var subFolder = await folder.CreateFolderAsync(folderName, folderCollisionOption);
                return await subFolder.CreatePathAsync(fileLocation.Substring(separatorIndex + 1), fileCollisionOption, folderCollisionOption);
            }
        }

        public static IAsyncActionWithProgress<string> Unzip(string zipUrl, string destinationUrl)
        {
            return AsyncInfo.Run<string>(async (_, progress) =>
            {
                // Create destination fileLocation
                var destination = await GetOrCreateFolderAtUrlAsync(destinationUrl);

                var zipFile = await StorageFile.GetFileFromApplicationUriAsync(new Uri(zipUrl));
                var zipStream = await zipFile.OpenReadAsync();
                using (var zip = new ZipArchive(zipStream.AsStreamForRead(), ZipArchiveMode.Read))
                {
                    var totalCount = zip.Entries.Count;
                    var extracted = 0;
                    var progressJson = new JsonObject { { "total", JsonValue.CreateNumberValue(totalCount) } };
                    
                    foreach (var entry in zip.Entries)
                    {
                        var storageItem = await destination.CreatePathAsync(entry.FullName, CreationCollisionOption.OpenIfExists, CreationCollisionOption.OpenIfExists);
                        StorageFile file;
                        if ((file = storageItem as StorageFile) != null)
                        {
                            using (var fileStream = await file.OpenStreamForWriteAsync())
                            {
                                using (var entryStream = entry.Open())
                                {
                                    await entryStream.CopyToAsync(fileStream);
                                }
                            }
                        }

                        progressJson.SetNamedValue("loaded", JsonValue.CreateNumberValue(++extracted));
                        progress.Report(progressJson.Stringify());
                    }
                }
            });
        }
    }
}
