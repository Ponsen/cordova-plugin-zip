cordova.commandProxy.add("Zip", {
    unzip: function(success, error, args) {
        Zip.Zip.unzip(args[0], args[1])
            .then(success, error, function(progress) {
                var progressObj = JSON.parse(progress);
                success(progressObj, { keepCallback: true });
            }).done();
    }
});