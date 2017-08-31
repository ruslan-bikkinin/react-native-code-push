var links = [
    require("./ios/postlink"),
    require("./android/postlink")
];

//run it sequentially
var result = links.reduce((p, fn) => p.then(fn), Promise.resolve());
result.catch((err) => {
    console.error(err.message);
}); 