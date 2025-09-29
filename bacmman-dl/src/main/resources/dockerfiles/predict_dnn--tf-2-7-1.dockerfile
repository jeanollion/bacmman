FROM tensorflow/tensorflow:2.7.1-gpu
RUN apt-key del 7fa2af80
RUN apt-key adv --fetch-keys https://developer.download.nvidia.com/compute/cuda/repos/ubuntu2004/x86_64/3bf863cc.pub
RUN apt-get -y clean
RUN apt-get -y update
RUN apt-get -y install wget
RUN pip install --upgrade h5py==3.11.0

RUN wget https://gist.githubusercontent.com/jeanollion/7b156bdbd7769f7a0a64b6774550ff4d/raw/predict_dev.py -O predict.py
RUN chmod a+r predict.py

CMD ["python", "predict.py"]