#!/usr/bin/env python3
"""Isolated SFTP server used by Android connected tests.

Password auth is always enabled for the fixed notebook/test-password pair.
Pass --authorized-keys to also accept the given public keys, which lets
desktop tests exercise private-key (publickey) authentication without any
password.
"""
import argparse, base64, hashlib, os, re, shlex, socket, subprocess, threading, time, traceback
import paramiko

def load_authorized_keys(path):
    keys = []
    if not path:
        return keys
    with open(path) as source:
        for line in source:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            try:
                keys.append(paramiko.PKey.from_type_string(parts[0], base64.b64decode(parts[1])))
            except (ValueError, IndexError, UnicodeDecodeError, paramiko.ssh_exception.UnknownKeyType):
                continue
    return keys

class Auth(paramiko.ServerInterface):
    def __init__(self, root, authorized_keys):
        self.root = os.path.realpath(root)
        self.authorized_keys = authorized_keys
    def check_auth_publickey(self, username, key):
        if username != "notebook":
            return paramiko.AUTH_FAILED
        return paramiko.AUTH_SUCCESSFUL if any(key.asbytes() == accepted.asbytes() for accepted in self.authorized_keys) else paramiko.AUTH_FAILED
    def check_auth_password(self, username, password):
        return paramiko.AUTH_SUCCESSFUL if (username,password)==("notebook","test-password") else paramiko.AUTH_FAILED
    def get_allowed_auths(self, username): return "publickey,password" if self.authorized_keys else "password"
    def check_channel_request(self, kind, chanid): return paramiko.OPEN_SUCCEEDED if kind=="session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
    def check_channel_exec_request(self, channel, command):
        # This fixture deliberately exposes only the two commands used by the
        # desktop repository helper.  In particular, never turn this into a
        # general-purpose shell for connected Android tests.
        try:
            text = command.decode("utf-8")
        except UnicodeDecodeError:
            text = ""
        thread = threading.Thread(target=self._exec, args=(channel, text), daemon=True)
        thread.start()
        return True
    def _helper_args(self, command):
        try:
            argv = shlex.split(command)
        except ValueError:
            return None
        if len(argv) != 3 or argv[:2] != ["python3", "-c"]:
            return None
        match = re.fullmatch(
            r"import base64,sys;exec\(base64\.b64decode\('([A-Za-z0-9+/=]+)'\)\);main\(base64\.b64decode\('([A-Za-z0-9+/=]+)'\)\.decode\(\),base64\.b64decode\('([A-Za-z0-9+/=]+)'\)\.decode\(\)\)",
            argv[2],
        )
        if not match:
            return None
        try:
            helper = base64.b64decode(match.group(1), validate=True).decode("utf-8")
            operation = base64.b64decode(match.group(2), validate=True).decode("utf-8")
            repository = base64.b64decode(match.group(3), validate=True).decode("utf-8")
        except (UnicodeDecodeError, ValueError):
            return None
        target = os.path.realpath(repository)
        if (operation not in {"export-tar", "import-tar", "verify", "gc"}
                or (target != self.root and not target.startswith(self.root + os.sep))
                or not helper.startswith("import gzip, hashlib, json")
                or "def main(op, root):" not in helper):
            return None
        return argv
    def _send_result(self, channel, stdout=b"", stderr=b"", status=0):
        try:
            if stdout:
                channel.sendall(stdout)
            if stderr:
                channel.send_stderr(stderr)
            channel.send_exit_status(status)
        finally:
            channel.close()
    def _exec(self, channel, command):
        # Paramiko sends the CHANNEL_SUCCESS reply after the request callback
        # returns.  Let that reply leave the transport before a very short
        # command closes its channel, otherwise ssh2 can report "Unable to
        # exec" for the next sequential helper channel.
        time.sleep(.01)
        if command == "pwd":
            self._send_result(channel, (self.root + "\n").encode("utf-8"))
            return
        argv = self._helper_args(command)
        if argv is None:
            self._send_result(channel, stderr=b"unsupported test fixture command\n", status=126)
            return
        try:
            chunks = []
            while True:
                chunk = channel.recv(65536)
                if not chunk:
                    break
                chunks.append(chunk)
            result = subprocess.run(
                argv, input=b"".join(chunks), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                cwd=self.root, timeout=60, check=False,
            )
            self._send_result(channel, result.stdout, result.stderr, result.returncode)
        except subprocess.TimeoutExpired:
            self._send_result(channel, stderr=b"repository helper test command timed out\n", status=124)
        except Exception as error:
            self._send_result(channel, stderr=("repository helper fixture failed: %s\n" % error).encode("utf-8"), status=1)

class SFTP(paramiko.SFTPServerInterface):
    def __init__(self, server, *a, root, **kw): super().__init__(server,*a,**kw);self.root=os.path.realpath(root)
    def _p(self,p):
        result=os.path.realpath(os.path.join(self.root,p.lstrip("/")))
        if result!=self.root and not result.startswith(self.root+os.sep): raise OSError(13,"outside root")
        return result
    def list_folder(self,path):
        try:
            out=[]
            for name in os.listdir(self._p(path)):
                attr=paramiko.SFTPAttributes.from_stat(os.stat(os.path.join(self._p(path),name)));attr.filename=name;out.append(attr)
            return out
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    def stat(self,path):
        try:return paramiko.SFTPAttributes.from_stat(os.stat(self._p(path)))
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    lstat=stat
    def open(self,path,flags,attr):
        try:
            p=self._p(path);os.makedirs(os.path.dirname(p),exist_ok=True);fd=os.open(p,flags,0o644)
            mode="r+b" if flags&os.O_RDWR else ("wb" if flags&os.O_WRONLY else "rb")
            f=os.fdopen(fd,mode);h=paramiko.SFTPHandle(flags);h.readfile=f;h.writefile=f;return h
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    def remove(self,path):
        try:os.remove(self._p(path));return paramiko.SFTP_OK
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    def rename(self,oldpath,newpath):
        try:os.replace(self._p(oldpath),self._p(newpath));return paramiko.SFTP_OK
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    def mkdir(self,path,attr):
        try:os.mkdir(self._p(path));return paramiko.SFTP_OK
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    def rmdir(self,path):
        try:os.rmdir(self._p(path));return paramiko.SFTP_OK
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    def chattr(self,path,attr):
        try:
            target=self._p(path)
            if attr.st_atime is not None or attr.st_mtime is not None:
                current=os.stat(target)
                os.utime(target,(attr.st_atime if attr.st_atime is not None else current.st_atime,attr.st_mtime if attr.st_mtime is not None else current.st_mtime))
            return paramiko.SFTP_OK
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    def canonicalize(self,path): return "/"+os.path.relpath(self._p(path),self.root).replace(os.sep,"/") if self._p(path)!=self.root else "/"

def main():
    ap=argparse.ArgumentParser();ap.add_argument("--port",type=int,default=2222);ap.add_argument("--root",required=True);ap.add_argument("--authorized-keys",default=None);args=ap.parse_args();os.makedirs(args.root,exist_ok=True)
    key=paramiko.RSAKey.generate(2048);fingerprint="SHA256:"+base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip("=")
    authorized_keys=load_authorized_keys(args.authorized_keys)
    print(fingerprint,flush=True)
    listener=socket.socket();listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);listener.bind(("127.0.0.1",args.port));listener.listen(20)
    while True:
        client,_=listener.accept()
        def serve(sock):
            t=paramiko.Transport(sock);t.add_server_key(key);t.set_subsystem_handler("sftp",paramiko.SFTPServer,SFTP,root=args.root)
            try:
                t.start_server(server=Auth(args.root, authorized_keys))
                # The desktop helper opens sequential exec channels (pwd, then
                # python3), while the existing tests use SFTP channels. Keep
                # accepting channels until the client disconnects.
                channels = []
                while t.is_active():
                    channel = t.accept(.2)
                    if channel is not None:
                        channels.append(channel)
            except Exception:traceback.print_exc()
            finally:t.close()
        threading.Thread(target=serve,args=(client,),daemon=True).start()
if __name__=="__main__":main()
