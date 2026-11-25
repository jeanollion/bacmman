"""
Prediction server used by BACMMAN's WSLEngine.

Started by BACMMAN (Windows) inside a dedicated WSL distribution with:
    wsl.exe -d <distro> --cd / --exec <venv python> bacmman_dl_server.py --gpus <list> [--allow-growth]
It loads a TensorFlow SavedModel and runs predictions on the arrays it receives.

Communication goes through the process' stdin / stdout (no files: file sharing between Windows and WSL is slow).
Every message, in both directions, is:
    uint32 (little endian) header length | header (UTF-8 JSON) | raw arrays
The header lists the arrays that follow ("arrays": [{"name", "dtype", "shape"}]); each array is sent as its raw
little-endian, C-ordered bytes. Images are in channels-last layout: N, (Z), Y, X, C.

Requests:  {"cmd": "load", "model": <path>}          -> {"status": "ok", "inputs": [...], "outputs": [...], ...}
           {"cmd": "predict", "id": k, "out_dtype": "float32"|"float16"} + one array per model input (in "inputs" order)
                                                     -> {"status": "ok", "id": k} + one array per output (in "outputs" order)
           {"cmd": "exit"}
Errors are reported as {"status": "error", "message": ..., "traceback": ...}.

Reading requests, predicting and writing results run in 3 threads, so that data transfers overlap with computation.
Log messages go to stderr: lines starting with "BACMMAN> " are displayed to the user.
"""
import argparse
import json
import os
import queue
import struct
import sys
import threading
import time
import traceback

PROTOCOL_VERSION = 1
ENV_VERSION_FILE = "/opt/bacmman-dl/VERSION"


def user_log(message):
    print(f"BACMMAN> {message}", file=sys.stderr, flush=True)


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gpus", default="", help="comma-separated list of GPU ids (nvidia-smi order). Empty: CPU")
    parser.add_argument("--allow-growth", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    # stdout is reserved for the protocol: anything else printing to stdout (python or native code) goes to stderr
    proto_out = os.fdopen(os.dup(1), "wb", buffering=1 << 20)  # buffered: write() then always writes everything
    os.dup2(2, 1)
    sys.stdout = sys.stderr
    proto_in = sys.stdin.buffer

    os.environ["CUDA_DEVICE_ORDER"] = "PCI_BUS_ID"  # same GPU ids as nvidia-smi
    os.environ["CUDA_VISIBLE_DEVICES"] = args.gpus if args.gpus.strip() else "-1"
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

    try:
        import numpy as np
        import tensorflow as tf
    except Exception as e:  # report to BACMMAN before exiting
        write_message(proto_out, {"status": "error", "message": f"Could not import tensorflow in the WSL distribution: {e}. Right-click on WSLEngine > Update WSL Environment.", "traceback": traceback.format_exc()}, [])
        return 1
    server = Server(np, tf, args)
    server.run(proto_in, proto_out)
    return 0


# ---------------------------------------------------------------------------------------------- messages
def read_exact_into(f, mv):
    pos = 0
    n = len(mv)
    while pos < n:
        r = f.readinto(mv[pos:])
        if not r:
            raise EOFError()
        pos += r


def read_message(f, np):
    size_bytes = f.read(4)
    if not size_bytes or len(size_bytes) < 4:
        raise EOFError()
    (size,) = struct.unpack("<I", size_bytes)
    header_bytes = bytearray(size)
    read_exact_into(f, memoryview(header_bytes))
    header = json.loads(header_bytes.decode("utf-8"))
    arrays = []
    for spec in header.get("arrays", []):
        arr = np.empty(spec["shape"], dtype=np.dtype(spec["dtype"]).newbyteorder("<"))
        if arr.size > 0:
            read_exact_into(f, memoryview(arr).cast("B"))
        arrays.append(arr)
    return header, arrays


def write_message(f, header, arrays, names=None):
    header = dict(header)
    # little-endian, C-ordered; dtype sent by name (float32, float16, int32...)
    arrays = [a.astype(a.dtype.newbyteorder("<"), order="C", copy=False) for a in arrays]
    if arrays:
        header["arrays"] = [{"name": names[i] if names else str(i), "dtype": a.dtype.name, "shape": list(a.shape)} for i, a in enumerate(arrays)]
    data = json.dumps(header).encode("utf-8")
    f.write(struct.pack("<I", len(data)))
    f.write(data)
    for a in arrays:
        if a.size > 0:
            f.write(memoryview(a.reshape(-1)).cast("B"))
    f.flush()


def error_header(message, request=None):
    h = {"status": "error", "message": message, "traceback": traceback.format_exc()}
    if request is not None and "id" in request:
        h["id"] = request["id"]
    return h


# ---------------------------------------------------------------------------------------------- server
class Server:
    def __init__(self, np, tf, args):
        self.np = np
        self.tf = tf
        self.args = args
        self.model = None  # keep a reference: the signature needs the loaded object alive
        self.signature = None
        self.input_names = None
        self.output_names = None
        self.input_dtypes = None
        self.gpus = []
        self.setup_gpus()

    def setup_gpus(self):
        tf = self.tf
        try:
            self.gpus = tf.config.list_physical_devices("GPU")
            if self.args.allow_growth:
                for gpu in self.gpus:
                    tf.config.experimental.set_memory_growth(gpu, True)
        except Exception as e:
            print(f"GPU configuration error: {e}", file=sys.stderr, flush=True)
        if self.gpus:
            user_log(f"TensorFlow {tf.__version__} in WSL: GPU(s) {', '.join(g.name for g in self.gpus)}")
        else:
            user_log(f"TensorFlow {tf.__version__} in WSL: no GPU visible, running on CPU")

    def load(self, request):
        tf = self.tf
        path = request["model"]
        if not os.path.isdir(path):
            raise FileNotFoundError(f"Model directory not found in WSL: {path}")
        t0 = time.time()
        self.model = tf.saved_model.load(path, tags=["serve"])
        self.signature = self.model.signatures["serving_default"]
        input_specs = self.signature.structured_input_signature[1]
        output_specs = self.signature.structured_outputs
        # same order as BACMMAN's TF2Engine: names sorted case-insensitively
        self.input_names = sorted(input_specs.keys(), key=str.lower)
        self.output_names = sorted(output_specs.keys(), key=str.lower)
        self.input_dtypes = {n: input_specs[n].dtype for n in self.input_names}
        print(f"model loaded in {time.time() - t0:.1f}s inputs: {self.input_names} outputs: {self.output_names}", file=sys.stderr, flush=True)
        env_version = None
        try:
            with open(ENV_VERSION_FILE) as f:
                env_version = f.read().strip()
        except OSError:
            pass
        return {"status": "ok", "inputs": self.input_names, "outputs": self.output_names,
                "input_shapes": [list(input_specs[n].shape.as_list()) if input_specs[n].shape.rank is not None else None for n in self.input_names],
                "tf_version": tf.__version__, "gpus": [g.name for g in self.gpus],
                "protocol": PROTOCOL_VERSION, "env_version": env_version}

    def predict(self, request, arrays):
        np, tf = self.np, self.tf
        if self.signature is None:
            raise RuntimeError("Model not loaded")
        if len(arrays) != len(self.input_names):
            raise ValueError(f"Model expects {len(self.input_names)} inputs, received {len(arrays)}")
        t0 = time.time()
        inputs = {n: tf.cast(tf.constant(a), self.input_dtypes[n]) if tf.as_dtype(a.dtype) != self.input_dtypes[n] else tf.constant(a)
                  for n, a in zip(self.input_names, arrays)}
        outputs = self.signature(**inputs)
        out_dtype = np.float16 if request.get("out_dtype") == "float16" else np.float32
        results = [np.ascontiguousarray(outputs[n].numpy().astype(out_dtype, copy=False)) for n in self.output_names]
        print(f"batch {request.get('id')}: prediction {time.time() - t0:.3f}s", file=sys.stderr, flush=True)
        return {"status": "ok", "id": request.get("id")}, results

    def run(self, proto_in, proto_out):
        np = self.np
        in_queue = queue.Queue(maxsize=2)    # bounds memory: at most 2 requests waiting
        out_queue = queue.Queue(maxsize=2)

        def reader():
            try:
                while True:
                    header, arrays = read_message(proto_in, np)
                    in_queue.put((header, arrays))
                    if header.get("cmd") == "exit":
                        return
            except EOFError:
                in_queue.put(None)
            except Exception:
                traceback.print_exc()
                in_queue.put(None)

        def writer():
            while True:
                item = out_queue.get()
                if item is None:
                    return
                header, arrays, names = item
                try:
                    write_message(proto_out, header, arrays, names)
                except (BrokenPipeError, OSError):
                    return  # BACMMAN closed the connection

        threading.Thread(target=reader, daemon=True).start()
        writer_thread = threading.Thread(target=writer, daemon=True)
        writer_thread.start()
        while True:
            item = in_queue.get()
            if item is None:
                break
            request, arrays = item
            cmd = request.get("cmd")
            if cmd == "exit":
                break
            try:
                if cmd == "load":
                    out_queue.put((self.load(request), [], None))
                elif cmd == "predict":
                    header, results = self.predict(request, arrays)
                    del arrays, item
                    out_queue.put((header, results, self.output_names))
                elif cmd == "ping":
                    out_queue.put(({"status": "ok", "id": request.get("id")}, [], None))
                else:
                    raise ValueError(f"Unknown command: {cmd}")
            except Exception as e:
                traceback.print_exc()
                out_queue.put((error_header(f"{type(e).__name__}: {e}", request), [], None))
        out_queue.put(None)
        writer_thread.join(timeout=10)


if __name__ == "__main__":
    sys.exit(main())
