import path from "path"
import { fileURLToPath } from "url"

const __dirname = path.dirname(fileURLToPath(import.meta.url))

export default {
  entry: "./target/scala-2.13/client-opt/main.js",
  output: {
    path: path.resolve(__dirname, "target/scala-2.13/client-opt/main.js"),
    filename: "bundle.js"
  },
}
