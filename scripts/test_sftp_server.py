#!/usr/bin/env python3
"""Isolated password-auth SFTP server used by Android connected tests."""
import argparse, base64, hashlib, os, socket, threading, time, traceback
import paramiko

class Auth(paramiko.ServerInterface):
    def check_auth_password(self, username, password):
        return paramiko.AUTH_SUCCESSFUL if (username,password)==("notebook","test-password") else paramiko.AUTH_FAILED
    def get_allowed_auths(self, username): return "password"
    def check_channel_request(self, kind, chanid): return paramiko.OPEN_SUCCEEDED if kind=="session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

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
    def canonicalize(self,path): return "/"+os.path.relpath(self._p(path),self.root).replace(os.sep,"/") if self._p(path)!=self.root else "/"

def main():
    ap=argparse.ArgumentParser();ap.add_argument("--port",type=int,default=2222);ap.add_argument("--root",required=True);args=ap.parse_args();os.makedirs(args.root,exist_ok=True)
    key=paramiko.RSAKey.generate(2048);fingerprint="SHA256:"+base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip("=")
    print(fingerprint,flush=True)
    listener=socket.socket();listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);listener.bind(("0.0.0.0",args.port));listener.listen(20)
    while True:
        client,_=listener.accept()
        def serve(sock):
            t=paramiko.Transport(sock);t.add_server_key(key);t.set_subsystem_handler("sftp",paramiko.SFTPServer,SFTP,root=args.root)
            try:
                t.start_server(server=Auth());channel=t.accept(20)
                while channel is not None and t.is_active():time.sleep(.2)
            except Exception:traceback.print_exc()
            finally:t.close()
        threading.Thread(target=serve,args=(client,),daemon=True).start()
if __name__=="__main__":main()
